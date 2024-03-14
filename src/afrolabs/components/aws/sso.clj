(ns afrolabs.components.aws.sso
  "Implements AWS profile authentication, but with access tokens sourced
  from the role bound to the federated SSO user.

  This reimplements the approach found in aws-wrap
  https://github.com/linaro-its/aws2-wrap
  with a couple of differences:
  - we assume existence of SSO profiles in ~/.aws/config (or mounted dir in a container)
  - we use the SSO credentials API directly - rather than shelling out to the AWS CLI (as it might not be
  present in all contexts)
  - we ignore assumed roles mechanism - we don't need it.

  PB update 14 March 2024
  I stole this code from https://gist.github.com/lukaszkorecki/120008f7832e23702e94f4205b8e3df5
  which I found on the bug for cognitect SSO support at: https://github.com/cognitect-labs/aws-api/issues/182"
  (:require

   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [cognitect.aws.config :as aws.config]
   [cognitect.aws.credentials :as aws.credentials]
   [java-time :as t])
  (:import
   (java.io
    File)
   (java.net
    HttpURLConnection
    URL)
   (java.time
    Instant)
   (java.util
    Date)))


(defn read-aws-config
  "Read the profile info from the main aws cli/sdk configuration file."
  [path profile]
  (let [f (io/file path)
        _ (log/infof "reading profile=%s" f)
        profiles (aws.config/parse f)
        profile-info (let [p (get profiles profile)]
                       (if (get p "sso_session")
                         (merge (get profiles (str "sso-session " (get p "sso_session")))
                                p)
                         p))]
    {:profile profile
     :start-url (get profile-info "sso_start_url" nil)
     :region (get profile-info "sso_region" nil)
     :account-id (get profile-info "sso_account_id" nil)
     :role-name (get profile-info "sso_role_name" nil)}))


(defn get-token-from-sso-cache
  "Traverse all cached credential files found in ~/.aws/sso/cache
  parse them (they're json) and return token if found in any of these files.
  There's always one with a valid token, but the name is auto-generated and it also might expire."
  [sso-cache-path]
  (log/infof "reading-sso-cache %s" sso-cache-path)
  (let [auth-data (->> sso-cache-path
                       io/file
                       file-seq
                       (filter (memfn ^File isFile))
                       ((fn [x]
                          (map #(println (.getName %)) x)
                          x))
                       (filter #(re-find #"json$" (.getName %)))
                       (map slurp)
                       (map #(json/parse-string % true))
                       (filter :accessToken)
                       first)
        {:keys [accessToken expiresAt]} auth-data]
    (when (and accessToken expiresAt
               ;; expiresAt here IS NOT iso8601 but some date-time str
               ;; with UTC appened (wtf) - so we have to convert it to a zoned date time
               ;; note that this is different expiration time and AWS creds expiration time:
               ;; - local access token as generated by SSO lasts 24 hrs
               ;; - aws creds fetched below expire within an hour usually
               ;; The former requires manual refresh via `aws sso login`
               ;; the latter will be refreshed by Cognitect's credentials machinery
               (not (t/before? (t/instant expiresAt)
                               (t/instant))))
      accessToken)))


(defn make-request [{:keys [token portal-url]}]
  (let [url (URL. portal-url)
        conn (.openConnection url)]
    (.setRequestProperty conn "x-amz-sso_bearer_token" token)
    (.setRequestMethod ^HttpURLConnection conn "GET")
    (.setDoOutput conn true)
    (.connect conn)
    (with-open [out (.getInputStream conn)]
      (-> (io/input-stream out)
          slurp
          (json/parse-string true)))))


(defn get-credentials-from-sso-api
  "Implements call to https://docs.aws.amazon.com/singlesignon/latest/PortalAPIReference/API_GetRoleCredentials.html"
  [{:keys [sso token]}]
  (let [url (format
              "https://portal.sso.%s.amazonaws.com:443/federation/credentials?account_id=%s&role_name=%s"
              (:region sso)
              (:account-id sso)
              (:role-name sso))
        _ (log/infof "requesting-token region=%s account-id=%s role=%s" (:region sso) (:account-id sso)  (:role-name sso))
        body (make-request {:token token :portal-url url})]
    (:roleCredentials body)))


(defn fetch-credentials-from-sso-profile
  "Fetches temporary AWS credentials for given config profile:
  - read sso info for given profile  from the aws config
  - parse out pre-authd SSO access token from the SSO cache
  - make a request to the AWS API to get the credentials"
  ([]
   (fetch-credentials-from-sso-profile (System/getenv "AWS_PROFILE")))
  ([profile-name]
   (fetch-credentials-from-sso-profile profile-name (or
                                                      (System/getenv "AWS_CONFIG_HOME")
                                                      (str (System/getenv "HOME") "/.aws"))))
  ([profile-name aws-root]
   (let [sso-config (read-aws-config (str aws-root "/config") profile-name)
         access-token (get-token-from-sso-cache (str aws-root "/sso/cache"))]
     (when-not access-token
       (throw
         (ex-info
           (str "AWS auth missing or expired, please log via SSO: aws sso login --profile="
                profile-name) {})))
     (get-credentials-from-sso-api {:sso sso-config
                                    :token access-token}))))


(defn fetch-from-profile [profile]
  (let [{:keys [accessKeyId secretAccessKey
                sessionToken expiration]} (fetch-credentials-from-sso-profile profile)
        ;; another incompatibility with other AWS APIs:
        ;; rather than sending iso8601 date, we get a unix timestamp
        ;; XXX: this is a bug in cognitect/aws-api:
        ;; internally calculate-ttl works with instants, so we wouldn't need the
        ;; Date/from call - but as it happens `inst?` predicate function works with both Date's a
        ;; and Instants... Waiting for the fix to be merged.4
        expiration-inst (Date/from (Instant/ofEpochMilli expiration))]
    {:aws/access-key-id accessKeyId
     :aws/secret-access-key secretAccessKey
     :aws/session-token sessionToken
     :cognitect.aws.credentials/ttl (aws.credentials/calculate-ttl {:Expiration expiration-inst})}))


(defn provider
  "Creates a credential provider which periodically refreshes credentials
  by using the SSO profile"
  [profile]
  (aws.credentials/cached-credentials-with-auto-refresh
    (reify aws.credentials/CredentialsProvider
      (fetch [_]
        (try
          (fetch-from-profile profile)
          (catch Exception e
            (log/errorf e "failed to refresh profile %s" profile)))))))
