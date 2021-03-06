(ns afrolabs.components.confluent.schema-registry-compatible-serdes
  "Provides a Confluent JSONSchema compatible serializer (for producers).

  Supports the wire-format: https://docs.confluent.io/platform/current/schema-registry/serdes-develop/index.html#wire-format"
  (:require [clojure.data.json :as json]
            [afrolabs.components.kafka :as -kafka]
            [taoensso.timbre :as log]
            [clojure.java.io :as io]
            [afrolabs.components.confluent.schema-registry :as -sr]
            [afrolabs.components :as -comp])
  (:import [org.apache.kafka.clients.producer ProducerConfig]
           [afrolabs.components.kafka IUpdateProducerConfigHook]
           [java.util UUID]
           [java.io ByteArrayOutputStream]
           [java.lang.ref Cleaner]
           [afrolabs.components IHaltable]))

(comment

  (compile 'afrolabs.components.confluent.schema-registry-compatible-serdes)

  )

;; Long essay:
;; A class that implements org.apache.kafka.common.serialization.Serializer is expected to have a zero-argument constructor.
;; This is because the producer thread will actually instantiate it. This also makse it hard to pass in context
;; and without extra trickery, this will make the class an effective singleton, which is problematic for creating components
;; that aim to be reusable, concurrently and in different contexts.
;; This class does support being configured (with textual values).
;; To aid in this, we will make use of an atom (schema-asserter-registry) which is a map from context-guids to
;; schema-asserters. We will then configured the producer with the correct context-guid (which is created invisibly and dynamically)

(defonce schema-asserter-registry (atom {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(gen-class :name       "afrolabs.components.confluent.sr_compat_serdes.Serializer"
           :prefix     "ser-"
           :main       false
           :init       "init"
           :state      "state"
           :implements [org.apache.kafka.common.serialization.Serializer])

(defn ser-init [] [[] (atom {})])

(defn ser-configure
  [this cfg key?]
  (swap! (.state this)
         assoc
         :context-guid (get cfg (str "afrolabs.components.confluent.sr_compat_serdes.Serializer.context-guid"))
         :key?         key?))

(defn int->4byte-array
  "Better way to do this... it may even work."
  [i]
  (let [bb (java.nio.ByteBuffer/allocate 4)]
    (.putInt bb i)
    (.array bb)))

(defn four-byte-array->int
  [four-byte-array]
  (let [bb (java.nio.ByteBuffer/wrap four-byte-array)]
    (.getInt bb)))

(defonce zero-byte-array (bytes (byte-array [0])))
(defn ser-serialize
  ([this topic data]
   (let [{:keys [context-guid
                 key?]}      @(.state this)
         schema-asserter     (get @schema-asserter-registry context-guid)
         schema-id           (-sr/get-schema-id schema-asserter
                                                (str topic "-" (if key? "key" "value")))
         bytes-output-stream (ByteArrayOutputStream.)
         schema-id-bytes     (int->4byte-array schema-id)]

     ;; write the 5 bytes header
     (.write bytes-output-stream zero-byte-array 0 1)  ;; first byte is always 0
     (.write bytes-output-stream schema-id-bytes 0 4)  ;; 4 bytes worth of schema-id integer

     ;; write the json string
     (with-open [bytes-writer        (io/writer bytes-output-stream)]
       (json/write data bytes-writer))

     (.toByteArray bytes-output-stream)))
  ([this topic _headers data]
   (ser-serialize this topic data)))

(defn ser-close [this] nil)


;;;;;;;;;;;;;;;;;;;;

;; A value serializer, that works like JsonSerializer
;; except it supports the confluent wire format for specifying which schema-id is compatible with the json payload
;; Only supports producing.
(-kafka/defstrategy ConfluentJSONSchemaCompatibleSerializer
  [& {:keys           [schema-asserter
                       producer]
      :or             {producer :value}}]

  (let [allowed-values #{:key :value :both}]
    (when-not (allowed-values producer)
      (throw (ex-info (format "ConfluentJSONSchemaCompatibleSerializer expects one of '%s' for :producer."
                              (str allowed-values))
                      {::allowed-values allowed-values
                       ::producer       producer}))))

  (let [context-guid (str (UUID/randomUUID))]
    (swap! schema-asserter-registry assoc context-guid schema-asserter)
    (reify
      IUpdateProducerConfigHook
      (update-producer-cfg-hook
          [_ cfg]
        (cond->                       (assoc cfg                                          "afrolabs.components.confluent.sr_compat_serdes.Serializer.context-guid" context-guid)
          (#{:both :key}   producer)  (assoc ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG   "afrolabs.components.confluent.sr_compat_serdes.Serializer")
          (#{:both :value} producer)  (assoc ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG "afrolabs.components.confluent.sr_compat_serdes.Serializer")))

      IHaltable
      (halt [_]
        (swap! schema-asserter-registry
               dissoc context-guid)))))

