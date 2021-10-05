(ns afrolabs.components.confluent.schema-registry
  (:require  [afrolabs.components :as -comp]
             [afrolabs.components.kafka :as -kafka]
             [clojure.spec.alpha :as s]
             [reitit.core :as reitit]
             [org.httpkit.client :as http-client]
             [clojure.data.json :as json]
             [net.cgrand.xforms :as x]
             [taoensso.timbre :as log])
  (:import [afrolabs.components.kafka
            IUpdateConsumerConfigHook
            IUpdateProducerConfigHook
            IUpdateAdminClientConfigHook]
           [clojure.lang ExceptionInfo]))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn valid-json? [s]
  (try
    (json/read-str s)
    (catch Throwable _ false)))

(s/def :provided-schema/schema (s/and string?
                                      #(pos-int? (count %))
                                      valid-json?))
(s/def :provided-schema/subject (s/and string?
                                       #(pos-int? (count %))))

(s/def ::get-subject-json-schemas (s/coll-of (s/keys :req-un [:provided-schema/subject
                                                              :provided-schema/schema])))

;;;;;;;;;;;;;;;;;;;;

(defprotocol ISubjectJSONSchemaProvider
  "A client protocol to provide a map between SUBJECT names and json schemas. The confluent schema asserter will consume this protocol against the schema registry.

  The result must conform to ::get-subject-json-schemas, which conforms on a collection of maps, each contains a :subject & :schema.

  - Schemas are JSON-encoded strings

  Eg1: [{:subject \"topic-key\"    :schema \"<JSON Schema 1>\"}]
  Eg2: [{:subject \"topic-value\"  :schema \"<JSON Schema 2>\"}]
  Eg3: [{:subject \"some-subject\" :schema \"<JSON Schema 3>\"}
        {:subject \"moar-subject\" :schema \"<JSON Schema 4>\"}] "
  (get-subject-json-schemas [_] "Returns a collection of subject names with JSON schemas in string format."))

(defprotocol IConfluentSchemaAsserter
  "A protocol for using the schema registry, eg mapping from subject names to schema id's"
  (get-schema-id [_ subject-name] "Returns the most recent known schema id for this SUBJECT."))

;;;;;;;;;;;;;;;;;;;;

(s/def ::subject-json-schema-provider #(satisfies? ISubjectJSONSchemaProvider %))
(s/def ::subject-json-schema-providers (s/coll-of ::subject-json-schema-provider))

(s/def ::schema-registry-url (s/and string?
                                    #(pos-int? (count %))))
(s/def ::schema-registry-api-key (s/or :n nil?
                                       :s (s/and string?
                                                 #(pos-int? (count %)))))
(s/def ::schema-registry-api-secret ::schema-registry-api-key)

(s/def ::confluent-schema-asserter-cfg (s/keys :req-un [::subject-json-schema-providers
                                                        ::schema-registry-url
                                                        ::schema-registry-api-secret
                                                        ::schema-registry-api-key]))

;;;;;;;;;;;;;;;;;;;;
;; HTTP/REST utilities for confluent schema registry API

(defn make-default-http-options
  [{:keys [schema-registry-api-key
           schema-registry-api-secret]}]
  (cond->
      {:user-agent (format "Afrolabs Confluent Schema Registry API Client")
       :headers    {"Accept"       "application/vnd.schemaregistry.v1+json"
                    "Content-Type" "application/vnd.schemaregistry.v1+json"}
       :as         :text}
    (and schema-registry-api-key
         schema-registry-api-secret)
    (assoc
     :basic-auth  [schema-registry-api-key
                   schema-registry-api-secret])))

(def confluent-api-router
  (reitit/router
   [["/schemas/ids/:id"                              ::schema-by-id]
    ["/schemas/types"                                ::schema-types]
    ["/schemas/ids/:id/versions"                     ::schema-versions]
    ["/subjects"                                     ::subjects]
    ["/subjects/:subject"                            ::subject]
    ["/subjects/:subject/versions"                   ::subject-versions]
    ["/subjects/:subject/versions/:version"          ::subject-version]
    ["/subjects/:subject/versions/:version/schema"   ::subject-version-schema]
    ["/config"                                       ::cluster-config]
    ["/config/:subject"                              ::subject-config]
    ]))

(defn make-url-fn
  "Creates an fn that returns the URL to a Confluent REST API based on the ::route-name and optional URL parameters."
  [schema-registry-url]
  (fn url
    ([route-name]
     (url route-name nil))
    ([route-name params]
     (let [route (reitit/match-by-name confluent-api-router route-name)
           the-url (cond
                     (nil? route)
                     (throw (ex-info (format "Unknown confluent path, name = '%s'" (str route-name))
                                     {:route-name route-name}))

                     (reitit/partial-match? route)
                     (let [path-params  (or (when params (select-keys params (:required route)))
                                            {})
                           query-params (or (when params (select-keys params (for [k (keys params) :when (not (contains? (:required route) k))] k)))
                                            {})
                           route (reitit/match-by-name! confluent-api-router route-name path-params)]
                       (reitit/match->path route query-params))

                     :else
                     (reitit/match->path route params))]
       (str schema-registry-url the-url)))))

(comment

  (reitit/match-by-name confluent-api-router ::schema-by-id)

  (let [make-url (make-url-fn "https://confluent.schema.registry.com")]
    (make-url ::schema-by-id {:id 1}))

  (let [make-url (make-url-fn "https://confluent.schema.registry.com")]
    (make-url ::schema-types {}))

  (let [make-url (make-url-fn "https://confluent.schema.registry.com")]
    (make-url ::schema-versions {:id 1}))

  (let [make-url (make-url-fn "https://confluent.schema.registry.com")]
    [(make-url ::subjects {:deleted true})
     (make-url ::subjects {})
     (make-url ::subjects)])

  )

(defn- cleanup-api-result
  "Helper fn; clears out auth data and decodes json strings."
  [result]
  (cond-> result
    (get-in result [:opts :body])
    (update-in [:opts :body] json/read-str)

    true
    (update :opts dissoc :basic-auth)))

(defn api-result
  "Throws on API errors, cleans up the results."
  [http-result]
  (let [result @http-result
        expected-types #{"application/vnd.schemaregistry.v1+json"
                         "application/vnd.schemaregistry+json"
                         "application/json"}
        actual-content-type (get-in result [:headers :content-type])]

    (when (not (expected-types actual-content-type))
      (throw (ex-info "Unsupported Content-Type from confluent API."
                      {:expected-types expected-types
                       :actual         actual-content-type})))

    (let [result (update result :body json/read-str)]
      (when (get-in result [:body "error_code"])
        (throw (ex-info (format "Schema Registry Error: Code='%d', Message='%s'"
                                (get-in result [:body "error_code"])
                                (get-in result [:body "message"]))
                        {:response (:body result)
                         :request  (-> result
                                       (dissoc :body)
                                       cleanup-api-result)
                         :http-status (:status result)})))


      (cleanup-api-result result))))

;;;;;;;;;;;;;;;;;;;;

(defn assert-server-supports-schema-type
  [make-url options schema-type]

  (let [{:keys [body]}
        (api-result (http-client/get (make-url ::schema-types)
                                     options))]
    (when-not (contains? (set body)
                         schema-type)
      (throw (ex-info (format "Schema Registry not configured to use '%s'" schema-type)
                      {:supported-schema-types body})))))

(defn upload-subject-schema
  "Uploads a schema to a subject. Both subject and schema must be strings."
  [make-url options subject schema]
  (api-result (http-client/post (make-url ::subject-versions {:subject subject})
                                (assoc options
                                       :body (json/write-str {:schema     schema
                                                              :schemaType "JSON"})))))

;;;;;;;;;;;;;;;;;;;;

(defn make-component
  [{:as   cfg
    :keys [subject-json-schema-providers
           schema-registry-url]}]
  (s/assert ::confluent-schema-asserter-cfg cfg)
  (let [make-url             (make-url-fn schema-registry-url)
        def-opts             (make-default-http-options cfg)]

    ;; verify that the schema-registry supports jsonschema
    (assert-server-supports-schema-type make-url
                                        def-opts
                                        "JSON")

    ;; get schemas; test if they were passed into this component correctly
    (let [all-provided-schemas (mapcat #(get-subject-json-schemas %)
                                       subject-json-schema-providers)]

      ;; throw an error to the developer if ITopicJSONSchemaProvider was implemented incorrectly
      (when (not (s/valid? ::get-subject-json-schemas all-provided-schemas))
        (throw (ex-info "JSON Schemas were provided in the wrong format."
                        {:explanation-str  (s/explain-str ::get-subject-json-schemas all-provided-schemas)
                         :explanation-data (s/explain-data ::get-subject-json-schemas all-provided-schemas)})))

      ;; We can upload the schemas we have to the endpoint for a subject.
      ;; If the schema is exactly the same, we'll get the existing/old schema-id back.
      ;; If it's brand new, it will similarly work, with a new id.
      ;; If the schema is new and compatible, we'll get the new id.
      ;; If the schema is IN-compatible, we'll get an exception.
      ;; This must prevent the app from starting up because an intervention is required to resolve the schema incompatibility.
      (let [{:keys [error
                    success]}
            (into {}
                  (comp
                   ;; upload schemas to schema registry
                   (map (fn [{:keys [subject schema]}]
                          (try
                            [:success
                             {:subject subject
                              :schema-id (get-in
                                          (upload-subject-schema make-url
                                                                 def-opts
                                                                 subject
                                                                 schema)
                                          [:body "id"])}]
                            (catch ExceptionInfo ei
                              [:error
                               (assoc (ex-data ei)
                                      :subject subject)]))))

                   ;; sort according to error/success status
                   ;; collect only the result of the api call
                   (x/by-key first (comp
                                    (map second)
                                    (map (fn [{:as x :keys [subject]}]
                                           [subject x]))
                                    (x/into {}))))
                  all-provided-schemas)]

        ;; throw when schema uploads produced errors
        (when (seq error)
          (let [err-msg (format "The Schema Registry asserting component cannot start because of incompatible JSONSchemas.\nThese subjects failed: %s"
                                (->> error
                                     keys
                                     sort
                                     (into [])))]
            (log/error err-msg)
            (throw (ex-info err-msg {:incompatible-schemas (into [] error)}))))

        (reify
          IConfluentSchemaAsserter
          (get-schema-id [_ subject]
            (get-in success [subject :schema-id])))))))

(comment

  (require '[afrolabs.config :as -config])

  (def cfg
    (let [cfg-source (-config/read-parameter-sources ".env")]
      {:schema-registry-url         (:confluent-schema-registry-url cfg-source)
       :schema-registry-api-key     (:confluent-schema-registry-api-key cfg-source)
       :schema-registry-api-secret  (:confluent-schema-registry-api-secret cfg-source)
       :topic-json-schema-providers []}))

  (def make-url (make-url-fn cfg))
  (def def-opts (make-default-http-options cfg))

  (def schema-asserter
    (let [cfg-source (-config/read-parameter-sources ".env")]
      (make-component {:schema-registry-url           (:confluent-schema-registry-url cfg-source)
                       :schema-registry-api-key       (:confluent-schema-registry-api-key cfg-source)
                       :schema-registry-api-secret    (:confluent-schema-registry-api-secret cfg-source)
                       :subject-json-schema-providers [(reify
                                                         ISubjectJSONSchemaProvider
                                                         (get-subject-json-schemas [_]
                                                           [{:subject "test-topic-key"
                                                             :schema  (json/write-str {:type                 "object",
                                                                                       :properties           {:b {:type "boolean"}
                                                                                                              :s {:type "string"}}
                                                                                       :required             [:b :s]})}]
                                                           ))]})))

  (get-schema-id schema-asserter "test-topic-value")

  (upload-subject-schema make-url
                         def-opts
                         "test-topic-key"
                         simple-schema-json)

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (require '[malli.core :as malli])
  (require '[malli.json-schema :as malli-json])

  (def simple-schema [:map
                      [:b boolean?]
                      [:s string?]])
  (def simple-schema-2 [:map
                        [:b boolean?]
                        [:s string?]
                        [:i {:optional true} int?]])
  (def simple-schema-4 [:map {:closed true}
                        [:s string?]
                        [:ss {:optional true} string?]])

  (def simple-schema-json (malli-json/transform simple-schema))
  (def simple-schema-json-2 (malli-json/transform simple-schema-2))
  (def simple-schema-json-4 (malli-json/transform simple-schema-4))

  

  )

;;;;;;;;;;;;;;;;;;;;

(-comp/defcomponent {::-comp/ig-kw       ::confluent-schema-asserter
                     ::-comp/config-spec ::confluent-schema-asserter-cfg}
  [cfg] (make-component cfg))
