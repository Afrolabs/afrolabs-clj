(ns afrolabs.components.aws.sqs
  (:require [afrolabs.components :as -comp]
            [afrolabs.components.aws :as -aws]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.retry]
            [clojure.spec.alpha :as s]
            [clojure.core.async :as csp]
            [taoensso.timbre :as log]
            [afrolabs.components.health :as -health]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.pprint]))

(def default-visibility-time-seconds 30)

(comment

  (def cfg (afrolabs.config/load-config "../.env"))
  (def sqs-client (make-sqs-client (-aws/make-creds-with-region (:sqs-aws-access-key-id cfg)
                                                                (:sqs-aws-secret-access-key cfg)
                                                                "eu-west-1")))

  (require '[afrolabs.components.aws.sns :as -sns])

  (def sns-client (-sns/make-sns-client (-aws/make-creds-with-region (:sns-aws-access-key-id cfg)
                                                                     (:sns-aws-secret-access-key cfg)
                                                                     "eu-west-1")))


  (def all-topics (-sns/query-all-topics {:sns-client sns-client}))

  (def fifo-topics (into []
                         (comp (filter (comp #(str/ends-with? % ".fifo")
                                             :TopicArn)))
                         all-topics))

  (aws/doc @sqs-client :CreateQueue)

  (let [{:keys [QueueUrl
                QueueArn]}
        (upsert-fifo-queue! {:sqs-client sqs-client}
                            :QueueName "pieter-test")]
    (def queue-arn QueueArn)
    (def queue-url QueueUrl))


  ;; we need to set up the queue so that it can accept messages from sns
  (let [topic-arn-wildcard-pattern (->> fifo-topics
                                        (map :TopicArn)
                                        (yoco.sns2kafka.sqs-config/find-sns-source-arn-pattern))
        queue-policy {"Version"   "2012-10-17"
                      "Statement" [{"Sid"       (str "policy-" (last (str/split queue-arn #":")))
                                    "Effect"    "Allow"
                                    "Action"    "sqs:SendMessage"
                                    "Principal" {"AWS" "*"}
                                    "Resource"  queue-arn
                                    "Condition" {"ArnLike" {"aws:SourceArn" topic-arn-wildcard-pattern}}}]}]
    (aws/invoke @sqs-client {:op      :SetQueueAttributes
                             :request {:QueueUrl   queue-url
                                       :Attributes {"Policy" (json/write-str queue-policy)}}}))


  ;; actual subscription
  (doseq [topic-arn (map :TopicArn fifo-topics)]
    (aws/invoke @sns-client {:op      :Subscribe
                             :request {:TopicArn topic-arn
                                       :Protocol "sqs"
                                       :Endpoint queue-arn}}))


  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; queuery
  (aws/doc @sqs-client :ReceiveMessage)
  (aws/invoke @sqs-client
              {:op         :ReceiveMessage
               :request    {:QueueUrl              queue-url
                            :WaitTimeSeconds       10
                            :MaxNumberOfMessages   1
                            :AttributeNames        ["MessageGroupId"]
                            :MessageAttributeNames ["All"]}
               :retriable? sqs-retriable})
  )


(def sqs-retriable
  (fn [{:cognitect.anomalies/keys [category
                                   message]
        :as                       error-info}]
    (or (cognitect.aws.retry/default-retriable? error-info)
        (and (= :cognitect.anomalies/fault category)
             (= "Abruptly closed by peer" message)))))

(defn upsert-queue!
  "Upserts a standard/non-fifo AWS SQS queue."
  [{:keys [sqs-client]}
   & {:keys [QueueName
             visibility-time-seconds]}]

  (let [{:keys [QueueUrl]
         :as   create-queue-result}
        (-aws/throw-when-anomaly
         (aws/invoke @sqs-client
                     {:op         :CreateQueue
                      :request    {:QueueName  QueueName
                                   :Attributes {"VisibilityTimeout" (str (or visibility-time-seconds
                                                                             default-visibility-time-seconds))}}
                      :retriable? sqs-retriable}))

        {{:keys [QueueArn]} :Attributes
         :as                get-queue-attributes-result}
        (-aws/throw-when-anomaly
         (aws/invoke @sqs-client
                     {:op         :GetQueueAttributes
                      :request    {:QueueUrl       QueueUrl
                                   :AttributeNames ["QueueArn"]}
                      :retriable? sqs-retriable}))
        ]
    {:QueueUrl QueueUrl
     :QueueArn QueueArn}))

(defn upsert-fifo-queue!
  "Upserts a FIFO AWS SQS queue. QueueName may NOT with .fifo, as it will be added automatically if not already present."
  [{:keys [sqs-client]}
   & {:keys [QueueName
             visibility-time-seconds
             fifo-high-throughput?
             content-based-deduplication?]
      :or {fifo-high-throughput?        false
           content-based-deduplication? false}}]
  {:pre [(boolean? content-based-deduplication?)]}

  (let [QueueName (if (str/ends-with? QueueName ".fifo") QueueName
                      (str QueueName ".fifo"))
        {:keys [QueueUrl]
         :as   create-queue-result}
        (-aws/throw-when-anomaly
         (aws/invoke @sqs-client
                     {:op      :CreateQueue
                      :request {:QueueName  QueueName
                                :Attributes (cond-> {"VisibilityTimeout"         (str (or visibility-time-seconds
                                                                                          default-visibility-time-seconds))
                                                     "FifoQueue"                 "true"
                                                     "ContentBasedDeduplication" (str content-based-deduplication?)}

                                              fifo-high-throughput?
                                              (assoc "DeduplicationScope"  "messageGroup"
                                                     "FifoThroughputLimit" "perMessageGroupId")

                                              )}
                      :retriable? sqs-retriable}))

        {{:strs [QueueArn]} :Attributes
         :as                get-queue-attributes-result}
        (-aws/throw-when-anomaly
         (aws/invoke @sqs-client
                     {:op         :GetQueueAttributes
                      :request    {:QueueUrl       QueueUrl
                                   :AttributeNames ["QueueArn"]}
                      :retriable? sqs-retriable}))

        ]
    {:QueueUrl QueueUrl
     :QueueArn QueueArn}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol ISqsClient)
(defprotocol ISqsClient2
  (get-sqs-client [_] "Returns an instance of aws/client"))
(s/def ::sqs-client #(or (satisfies? ISqsClient %)
                         (satisfies? ISqsClient2 %)))

(defn- make-derefable-from-client
  "This is a hack to allow backwards API compatibility.

  Makes something that can be `deref`-ed, based on different ways of providing an
  sqs-client."
  [sqs-client-impl]
  (cond
    (satisfies? ISqsClient sqs-client-impl)
    (reify
      clojure.lang.IDeref
      (deref [_] @sqs-client-impl))

    (satisfies? ISqsClient2 sqs-client-impl)
    (reify
      clojure.lang.IDeref
      (deref [_] (get-sqs-client sqs-client-impl)))))

;;;;;;;;;;;;;;;;;;;;

(s/def ::sqs-client-cfg (s/keys :req-un [::-aws/aws-creds-component
                                         ::-aws/aws-region-component]))

(defn make-sqs-client
  [{:keys [aws-creds-component
           aws-region-component]}]
  (let [state (aws/client {:api                  :sqs
                           :credentials-provider aws-creds-component
                           :region               (:region aws-region-component)})]
    (reify
      -comp/IHaltable
      (halt [_] (aws/stop state))


      ;; marker protocol
      ISqsClient

      clojure.lang.IDeref
      (deref [_] state))))

(-comp/defcomponent {::-comp/config-spec ::sqs-client-cfg
                     ::-comp/ig-kw       ::sqs-client}
  [cfg] (make-sqs-client cfg))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol ISqsQueueProvider
  "Can provide the QueueUrl. This is useful if the QueueUrl is not known a priori, and must be created by another component."
  (get-sqs-queue-url [_] "Returns the queue url.")
  (get-sqs-queue-arn [_] "Returns the queue arn."))

(defprotocol ISqsConsumerClient
  "A protocol for a sqs message handler."
  (consume-message [_ msg delete-ch] "Consumes a single message. When the message may be deleted, deliver the original message to the delete-ch."))

(s/def ::sqs-consumer-client #(satisfies? ISqsConsumerClient %))
(s/def ::QueueUrl (s/and string?
                         #(pos-int? (count %))))
(s/def ::queue-provider #(satisfies? ISqsQueueProvider %))
(s/def ::wait-time-seconds pos-int?)
(s/def ::visibility-timeout pos-int?)
(s/def ::max-nr-of-messages (s/and pos-int?
                                   #(> 11 %)))
(s/def ::AttributeNames (s/coll-of (s/and string?
                                          #(pos-int? (count %)))))
(s/def ::sqs-consumer-cfg (s/and (s/keys :req-un [::sqs-consumer-client
                                                  ::sqs-client
                                                  ::-health/service-health-trip-switch]
                                         :opt-un [::wait-time-seconds
                                                  ::max-nr-of-messages
                                                  ::visibility-timeout
                                                  ::queue-provider
                                                  ::QueueUrl
                                                  ::AttributeNames])
                                 #(or (:QueueUrl %)
                                      (:queue-provider %))))

(comment

  (s/check-asserts true)

  )

(defn consumer-main
  [must-run
   {:keys [sqs-client
           sqs-consumer-client
           QueueUrl
           queue-provider
           wait-time-seconds
           max-nr-of-messages
           visibility-timeout
           service-health-trip-switch
           AttributeNames]
    :or   {wait-time-seconds  5 ;; 5 seconds is not really that long. could easily be 30 seconds and would save on compute resources
           max-nr-of-messages 5}
    :as   sqs-consumer-cfg}]
  (s/assert ::sqs-consumer-cfg sqs-consumer-cfg)
  (let [QueueUrl (or QueueUrl
                     (get-sqs-queue-url queue-provider))
        delete-ch (csp/chan max-nr-of-messages)
        sqs-client' (make-derefable-from-client sqs-client)
        message-delete-thread
        (csp/thread
          (try
            (loop []
              (when-let [{receipt-handle :ReceiptHandle} (csp/<!! delete-ch)] ;; v is nil only when channel is closed
                (log/trace (format "deleting sqs message: %s" receipt-handle))
                (-aws/throw-when-anomaly
                 (aws/invoke @sqs-client'
                             {:op         :DeleteMessage
                              :request    {:QueueUrl      QueueUrl
                                           :ReceiptHandle receipt-handle}
                              ;; there is a special kind of retriable error for :DeleteMessage on sqs
                              ;; https://clojurians-log.clojureverse.org/aws/2022-04-27
                              :retriable? sqs-retriable}))
                (recur)))
            (catch Throwable t
              (log/fatal t "Uncaught exception on the delete-thread for sqs consumer. Stopping...")
              (-health/indicate-unhealthy! service-health-trip-switch ::sqs-consumer)
              (reset! must-run false)
              (csp/close! delete-ch)))
          (log/trace "Done with sqs consumer-main loop's message-delete-thread."))]
    (try
      (while @must-run
        (let [{msgs :Messages}
              (-aws/throw-when-anomaly
               (aws/invoke @sqs-client'
                           {:op         :ReceiveMessage
                            :request    (cond-> {:QueueUrl            QueueUrl
                                                 :WaitTimeSeconds     wait-time-seconds
                                                 :MaxNumberOfMessages max-nr-of-messages}
                                          AttributeNames (assoc :AttributeNames AttributeNames)
                                          visibility-timeout (assoc :VisibilityTimeout visibility-timeout))
                            :retriable? sqs-retriable}))]


          ;; pass on every message to the consumer client (business code)
          ;; back-pressure comes from the pace at which the consumer client access the messages
          (doseq [msg msgs]
            (consume-message sqs-consumer-client
                             msg
                             delete-ch))))
      (catch Throwable t
        (log/error t "Uncaught exception in sqs consumer loop. Indicating unhealthy.")
        (-health/indicate-unhealthy! service-health-trip-switch ::sqs-consumer)))

    (log/trace "Done with sqs consumer-main loop. Closing delete-ch...")

    ;; when the outer loop is done because of not @must-run, close the delete-ch so the deleting thread can stop too
    (csp/close! delete-ch)

    (log/trace "Closed delete-ch. Waiting for message-delete-thread...")

    ;; wait for the delete thread to signal it's finished
    (csp/<!! message-delete-thread)

    (log/debug "sqs consumer-main done.")))

(defn make-sqs-consumer
  [cfg]
  (let [must-run (atom true)
        worker (csp/thread (consumer-main must-run cfg)
                           nil)]
    (log/info "SQS Consumer started.")
    (reify
      -comp/IHaltable
      (halt [_]
        (log/trace "sqs; Setting must-run to false...")
        (reset! must-run false)
        (log/trace "Waiting for worker thread to quit...")
        (csp/<!! worker)
        (log/info "SQS Consumer done.")))))

(-comp/defcomponent {::-comp/config-spec ::sqs-consumer-cfg
                     ::-comp/ig-kw       ::sqs-consumer}
  [cfg] (make-sqs-consumer cfg))
