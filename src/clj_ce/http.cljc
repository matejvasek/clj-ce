(ns clj-ce.http
  "This namespace contains functions for reading/writing
  CloudEvents from/to http messages.

  Http message in this context is a map with :headers and :body keys.
  (not unlikely ring request/response).
  The http message can be then used as http request or response.

  CloudEvent is here represented by a map
  where keys are namespaced keywords :ce/*.

  For instance #:ce{:id \"42\",
                    :spec-version \"1.0\",
                    :type \"my.type\",
                    :source \"http://example.com/\"}

  Examples:

  (clj-http.client/post \"http://localhost/\"
                        (event->binary-http #:ce{:id \"42\"
                                                 :spec-version \"1.0\"
                                                 :type \"my.type\"
                                                 :source \"http://example.com/\"}))

  (defn ring-echo-handler
    [req]
    (-> req
        (binary-http->event)
        (event->binary-http)
        (assoc :status 200)))"

  (:require [clj-ce.util :refer [parse-uri ser-time deser-time]]
            [clojure.string :refer [starts-with? index-of trim split]]
            [clojure.set :refer [map-invert]])
  #?(:clj (:import (java.time Instant))))

(def ^:private ser-uri str)

(def ^:private deser-uri parse-uri)

(def ^:private field->header-common
  #:ce{:id                "ce-id"
       :spec-version      "ce-specversion"
       :source            "ce-source"
       :type              "ce-type"
       :subject           "ce-subject"
       :data-content-type "content-type"
       :time              "ce-time"})

(def ^:private field->header-v1
  (merge field->header-common
         #:ce{:data-schema "ce-dataschema"}))

(def ^:private field->header-v03
  (merge field->header-common
         #:ce{:schema-url            "ce-schemaurl"
              :data-content-encoding "ce-datacontentencoding"}))

(def ^:private header->field-v1
  (map-invert field->header-v1))

(def ^:private header->field-v03
  (map-invert field->header-v03))

(def ^:private header->field-by-version
  {"0.3" header->field-v03
   "1.0" header->field-v1})

(def ^:private field->deser-fn
  #:ce{:time        deser-time
       :source      deser-uri
       :data-schema deser-uri
       :schema-url  deser-uri})

(defn- header->field&deser-fn-by-version
  "Returns a function that maps http header to a pair [field, deser-fn],
  where `field` is a field of CloudEvent (keyword) to which the http header is mapped to and
  `deser-fn` is a function used to deserialize the header to the field."
  [version]
  (fn [header]
    (if-let [header->field (header->field-by-version version)]
      (if (header->field header)
        [(header->field header)
         (field->deser-fn (header->field header) identity)]))))

(def ^:private field->header-by-version
  {"0.3" field->header-v03
   "1.0" field->header-v1})

(def ^:private field->ser-fn
  #:ce{:time        ser-time
       :source      ser-uri
       :data-schema ser-uri
       :schema-url  ser-uri})

(defn- field->header&ser-fn-by-version
  "Returns a function that maps CloudEvent field to pair [header, ser-fn],
  where `header` is a name of a header to which the filed (keyword) is mapped to and
  `ser-fn` is a function used to serialize the field to the header."
  [version]
  (fn [field]
    (if-let [field->header (field->header-by-version version)]
      (if (field->header field)
        [(field->header field)
         (field->ser-fn field identity)]))))

(defn- structured-http?
  [http-msg]
  (-> http-msg
      (:headers)
      (get "content-type" "")
      (starts-with? "application/cloudevents+")))

(defn- binary-http?
  [http-msg]
  (contains? (:headers http-msg) "ce-id"))

(defn- ce-http?
  [http-msg]
  (or (structured-http? http-msg)
      (binary-http? http-msg)))

(defn binary-http->event
  "Creates CloudEvent from http message in binary format."
  [{:keys [headers body]}]
  (let [headers (->> headers
                     (map (fn [[k v]]
                            [(.toLowerCase ^String k) v]))
                     (into headers))
        version (headers "ce-specversion")
        header->field&deser-fn (header->field&deser-fn-by-version version)
        rf (fn [event [header-key header-value]]
             (cond
               ;; ce field
               (header->field&deser-fn header-key)
               (let [[field deser-fn] (header->field&deser-fn header-key)]
                 (assoc event field (deser-fn header-value)))

               ;; ce extension field
               (and (starts-with? header-key "ce-") (> (count header-key) 3))
               (assoc-in event [:ce/extensions (keyword (subs header-key 3))] header-value)

               ;; non ce header
               :else
               event))
        event (reduce rf {} headers)]
    (if body
      (assoc event :ce/data body)
      event)))

(defn event->binary-http
  "Creates http message in binary mode from an event."
  [event]
  (let [field->header&ser-fn (field->header&ser-fn-by-version (:ce/spec-version event))
        headers (->> (:ce/extensions event)
                     (map (fn [[k v]] [(str "ce-" (name k)) v]))
                     (into {}))
        headers (->> (dissoc event :ce/extensions)
                     (keep (fn [[field-key field-value]]
                             (if-let [[header-key ser-fn] (field->header&ser-fn field-key)]
                               [header-key (ser-fn field-value)])))
                     (into headers))]

    {:headers headers
     :body    (:ce/data event)}))

(defn- parse-content-type
  "Returns format and charset of CloudEvent
  from content-type header of http message in structured mode.

  For instance for \"application/cloudevents+json; charset=utf-8\"
  returns [\"json\", \"utf-8\"]."
  [content-type]
  (let [[format-part charset-part] (split content-type #";")
        format-start (some-> (index-of format-part "+") inc)
        type (if (and format-part format-start)
               (subs format-part format-start)
               nil)
        charset (second (split (or charset-part "charset=ISO-8859-1") #"="))
        format (some-> type trim)
        charset (some-> charset trim)]
    (if format
      [format charset]
      ["application/octet-stream" nil])))

(defn structured-http->event
  "Creates CloudEvent from http message in structured mode."
  [http-msg deserializers]
  (let [{:keys [headers body]} http-msg
        [format encoding] (parse-content-type (headers "content-type"))
        deserialize-fn (deserializers format)]
    (deserialize-fn body encoding)))

(defn event->structured-http
  "Creates http message in structured mode from an event."
  [event format-name serialize-fn charset]
  {:headers {"content-type" (str "application/cloudevents+" format-name "; charset=" charset)}
   :body    (serialize-fn event)})

(defn http->event
  "Creates CloudEvent from http message in either binary or structured mode."
  [http-msg serializers]
  (cond
    (binary-http? http-msg) (binary-http->event http-msg)
    (structured-http? http-msg) (structured-http->event http-msg serializers)
    :else nil))