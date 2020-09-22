(ns web.auth
  (:require [web.config :refer [server-config]]
            [web.db :refer [db object-id]]
            [web.utils :refer [response]]
            [aero.core :refer [read-config]]
            [clj-time.core :refer [days from-now]]
            [monger.collection :as mc]
            [monger.result :refer [acknowledged? updated-existing?]]
            [monger.operators :refer :all]
            [buddy.sign.jwt :as jwt]
            [digest]
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.backends.session :refer [session-backend]]
            [crypto.password.bcrypt :as password]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [postal.core :as mail]
            [ring.util.response :refer [redirect]])
  (:import java.security.SecureRandom))

(def auth-config (:auth server-config))

(defn create-token [{:keys [_id emailhash]}]
  (let [claims {:_id _id
                :emailhash emailhash
                :exp (-> (:expiration auth-config) days from-now)}
        token (jwt/sign claims (:secret auth-config) {:alg :hs512})]
    (jwt/sign claims (:secret auth-config) {:alg :hs512})))

(defn unsign-token [token]
  (try (jwt/unsign token (:secret auth-config) {:alg :hs512})
       (catch Exception e (prn "Received invalid cookie " token))))

(defn wrap-authorization-required [handler]
  (fn [{user :user :as req}]
    (if (:isadmin user)
      (handler req)
      (response 401 {:message "Not authorized"}))))

(defn wrap-authentication-required [handler]
  (fn [{user :user :as req}]
    (if user
      (handler req)
      (response 401 {:message "Not authorized"}))))

(defn wrap-tournament-auth-required [handler]
  (fn [{user :user :as req}]
    (if (:tournament-organizer user)
      (handler req)
      (response 401 {:message "Not authorized"}))))

(defn wrap-user [handler]
  (fn [{:keys [cookies] :as req}]
    (let [user-keys [:_id :username :emailhash :isadmin :ismoderator :tournament-organizer :special :options :stats]
          auth-cookie (get cookies "session")
          user (when auth-cookie
                 (unsign-token (:value auth-cookie)))
          u (when user
              (mc/find-one-as-map db "users" {:_id (object-id (:_id user))
                                              :emailhash (:emailhash user)}))]
      (if u
        (handler (-> req
                     (assoc :user (select-keys u user-keys))
                     (update-in [:user :_id] str)))
        (handler req)))))

(defn mark-verified [email]
  (let [result (mc/update db "users"
                          {:email email}
                          {"$set" {:isverified true}})]
    (updated-existing? result)))

(defn verify-email
  [{{:keys [token]} :params}]
  (try
    (let [claims (jwt/unsign token (:secret auth-config))
          email (:email claims)]
      (if (:exp claims)
        (if (mark-verified email)
          (redirect "/")
          (response 500 {:message "Server error"}))
        (response 401 {:message "Not authorized"})))
    (catch Exception e
      (if-let [{ex-type :type cause :cause} (ex-data e)]
        (if (= ex-type :validation)
          (if (= cause :exp)
            (response 401 {:message "Token expired"})
            (response 401 {:message "Not authorized"})))
        (throw e)))))

(defn register-handler
  [{{:keys [username password confirm-password email]} :params
    :as request}]
  (cond
    (< 20 (count username))
    (response 401 {:message "Usernames are limited to 20 characters"})

    (not= password confirm-password)
    (response 401 {:message "Passwords must match"})

    (mc/find-one-as-map db "users" {:username {$regex (str "^" username "$") $options "i"}})
    (response 422 {:message "Username taken"})

    (mc/find-one-as-map db "users" {:email {$regex (str "^" email "$") $options "i"}})
    (response 424 {:message "Email taken"})

    :else
    (let [first-user (not (mc/any? db "users"))
          email-hash (digest/md5 email)
          registration-date (java.util.Date.)
          last-connection registration-date
          hash-pw (password/encrypt password)
          demo-decks (mc/find-maps db "decks" {:username "__demo__"})]
      (mc/insert db "users"
                 {:username         username
                  :email            email
                  :emailhash        email-hash
                  :registrationDate registration-date
                  :lastConnection   last-connection
                  :password         hash-pw
                  :isadmin          first-user
                  :isverified       first-user
                  :options          {}})
      (when (not-empty demo-decks)
        (mc/insert-batch db "decks" (map #(-> %
                                              (dissoc :_id)
                                              (assoc :username username))
                                         demo-decks)))
      (response 200 {:message "ok"}))))

(defn login-handler [{{:keys [username password]} :params
                      :as request}]
  (let [user (mc/find-one-as-map db "users" {:username username})]
    (if (and user
             (password/check password (:password user)))

      ; If the user doesn't have an isverified key, allow them to
      ; log in. This avoids breaking login for all legacy users.
      (if (get user :isverified true)
        (do (mc/update db "users"
                       {:username username}
                       {"$set" {:last-connection (java.util.Date.)}})
            (assoc (response 200 {:message "ok"})
                   :cookies {"session" (merge {:value (create-token user)}
                                              (get-in server-config [:auth :cookie]))}))
        (response 401 {:error "Please verify your email"}))
      (response 401 {:error "Invalid login or password"}))))

(defn logout-handler [request]
  (assoc (response 200 {:message "ok"})
    :cookies {"session" {:value 0
                         :max-age -1}}))

(defn check-username-handler [{{:keys [username]} :params}]
  (if (mc/find-one-as-map db "users" {:username {$regex (str "^" username "$")
                                                 $options "i"}})
    (response 422 {:message "Username taken"})
    (response 200 {:message "OK"})))

(defn check-email-handler [{{:keys [email]} :params}]
  (if (mc/find-one-as-map db "users" {:email {$regex (str "^" email "$")
                                              $options "i"}})
    (response 422 {:message "Username taken"})
    (response 200 {:message "OK"})))

(defn email-handler [{{username :username} :user
                      body                 :body}]
  (if username
    (let [{:keys [email]} (mc/find-one-as-map db "users" {:username {$regex (str "^" username "$")
                                                                     $options "i"}})]
      (response 200 {:email email}))
    (response 401 {:message "Unauthorized"})))

(defn change-email-handler [{{username :username} :user
                             {email :email}       :body
                             :as params}]
  (cond
    (not username)
    (response 401 {:message "Unauthorized"})

    (mc/find-one-as-map db "users" {:email email})
    (response 400 {:message "Email address already in use"})

    (acknowledged?
      (mc/update db "users"
                 {:username username}
                 {"$set" {:email email}}))
    (response 200 {:message "Refresh your browser"})

    :else
    (response 404 {:message "Account not found"})))

(defn update-profile-handler [{{username :username} :user
                               body                 :body}]
  (if username
    (if (acknowledged? (mc/update db "users"
                                  {:username username}
                                  {"$set" {:options (select-keys body [:background :pronouns :show-alt-art :blocked-users
                                                                       :alt-arts :deckstats :gamestats])}}))
      (response 200 {:message "Refresh your browser"})
      (response 404 {:message "Account not found"}))
    (response 401 {:message "Unauthorized"})))

(defn generate-secure-token
  [size]
  (let [seed (byte-array size)]
    (.nextBytes (SecureRandom/getInstance "SHA1PRNG") seed)
    seed))

(defn hexadecimalize
  "Converts a byte array to a hex string"
  [a-byte-array]
  (clojure.string/lower-case (clojure.string/join (map #(format "%02X" %) a-byte-array))))

(defn set-password-reset-code!
  "Generates a password-reset code for the given email address. Updates the user's info in the database with the code,
  and returns the code."
  [email]
  (let [reset-code (hexadecimalize (generate-secure-token 20))
        reset-expires (t/plus (t/now) (t/hours 1))]
    (mc/update db "users"
               {:email email}
               {"$set" {:resetPasswordToken reset-code
                        :resetPasswordExpires (c/to-date reset-expires)}})
    reset-code))

(defn forgot-password-handler
  [{{:keys [email]} :params
    headers         :headers}]
  (if-let [user (mc/find-one-as-map db "users" {:email email})]
    (let [code (set-password-reset-code! email)
          msg (mail/send-message
                (:email server-config)
                {:from    "support@jinteki.net"
                 :to      email
                 :subject "Jinteki Password Reset"
                 :body    (str "You are receiving this because you (or someone else) have requested the reset of the password for your account.\n\n"
                               "Please click on the following link, or paste this into your browser to complete the process:\n\n"
                               "http://" (headers "host") "/reset/" code "\n\n"
                               "If you did not request this, please ignore this email and your password will remain unchanged.\n")})]
      (if (zero? (:code msg))
        (response 200 {:message "Email sent"})
        (response 500 {:message (:message msg)})))
    (response 421 {:message "No account with that email address"})))

(defn reset-password-handler
  [{{:keys [password confirm token]} :params}]
  (if-let [{:keys [username email]} (mc/find-one-as-map db "users" {:resetPasswordToken   token
                                                                    :resetPasswordExpires {"$gt" (c/to-date (t/now))}})]
    (if (= password confirm)
      (let [hash-pw (password/encrypt password)]
        (mc/update db "users"
                   {:username username}
                   {"$set" {:password             hash-pw
                            :resetPasswordExpires nil
                            :resetPasswordToken   nil}})
        (mail/send-message
          (:email server-config)
          {:from    "support@jinteki.net"
           :to      email
           :subject "Your password has been changed"
           :body    (str "Hello,\n\n"
                         "This is a confirmation that the password for your account " email " has just been changed.\n")})
        (redirect "/"))
      (response 422 {:message "New Password and Confirm Password did not match"}))
    (response 404 {:message "No reset token found"})))
