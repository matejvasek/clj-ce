(ns clj-ce.json
  (:require [clj-ce.util :as util]
            [clojure.set :refer [map-invert]]
            #?(:clj [clojure.data.json :as json])
            #?(:cljs [goog.crypt.base64 :as b64]))
  #?(:clj (:import (java.io InputStream ByteArrayInputStream InputStreamReader PushbackReader StringReader)
                   (java.util Base64))))

(def ^:private js-field->clj-field-common
  {"id"              :ce/id
   "specversion"     :ce/spec-version
   "source"          :ce/source
   "type"            :ce/type
   "subject"         :ce/subject
   "datacontenttype" :ce/data-content-type
   "time"            :ce/time
   "data"            :ce/data
   "data_base64"     :ce/data})

(def ^:private js-field->clj-field-v1
  (conj js-field->clj-field-common
        ["dataschema" :ce/data-schema]))

(def ^:private js-field->clj-field-v03
  (conj js-field->clj-field-common
        ["schemaurl" :ce/schema-url]
        ["datacontentencoding" ::transient]))

(def ^:private clj-field->js-field-v1
  (map-invert js-field->clj-field-v1))

(def ^:private clj-field->js-field-v03
  (map-invert js-field->clj-field-v03))

(defn- ser-uri
  [clj-field-value _]
  (str clj-field-value))

(defn- deser-uri
  [js-field-value _]
  (util/parse-uri js-field-value))

(defn- ser-time
  [clj-field-value _]
  (util/ser-time clj-field-value))

(defn- deser-time
  [js-field-value _]
  (util/deser-time js-field-value))


(defn atob
  [s]
  #?(:clj (String. (.decode (Base64/getDecoder) ^String s) "UTF-8")
     :cljs (.decode (js/TextDecoder. "utf-8") (b64/decodeStringToUint8Array s))))

(defn- deser-data
  [field-value js-obj]
  (let [dce (js-obj "datacontentencoding")]
    (cond

      (and (= dce "base64")
           (string? field-value))
      (atob field-value)

      (string? field-value)
      field-value

      :else
      #?(:clj  (json/write-str field-value)
         :cljs (js/JSON.stringify (clj->js field-value))))))

(defn- deser-data-base-64
  [field-value _]
  (atob field-value))

(def ^:private field->deser-fn
  {"time"        deser-time
   "source"      deser-uri
   "dataschema"  deser-uri
   "schemaurl"   deser-uri
   "data_base64" deser-data-base-64
   "data"        deser-data})

(def ^:private js-field->ser-fn
  #:ce{:time        ser-time
       :source      ser-uri
       :data-schema ser-uri
       :schema-url  ser-uri})

(def js-field->clj-field-by-version
  {"1.0" js-field->clj-field-v1
   "0.3" js-field->clj-field-v03})

(defprotocol Data
  "Abstract various sources of data e.g. byte array or input stream"
  (->character-source [this charset]
    "For Clojure returns java.io.Reader for ClojureScript returns string"))

#?(:clj
   (extend-protocol Data
     (Class/forName "[B")
     (->character-source [data charset] (InputStreamReader. (ByteArrayInputStream. data) ^String charset))
     String
     (->character-source [data _] (StringReader. data))
     InputStream
     (->character-source [data charset] (InputStreamReader. ^InputStream data ^String charset))
     nil
     (->character-source [_ _] (StringReader. ""))))

#?(:cljs
   (extend-protocol Data
     string
     (->character-source [data _] data)
     js/Uint8Array
     (->character-source [data charset] (.decode (js/TextDecoder. charset) data))
     js/ArrayBuffer
     (->character-source [data charset] (.decode (js/TextDecoder. charset) (js/Uint8Array. data)))
     nil
     (->character-source [_ _] "")))

#?(:cljs
   (when (resolve 'js.Buffer)
     (extend-protocol Data
       js/Buffer
       (->character-source [data charset] (.toString data charset)))))


(defn- data->characters
  "Abstract various sources of data e.g. byte array or input stream.
  For Clojure returns java.io.Reader for ClojureScript returns string."
  [data & [charset]]
  (->character-source data charset))


(defn- data->obj
  "Transforms data to a map representing JS object."
  [data & [charset]]
  #?(:clj (json/read (PushbackReader. (data->characters data charset)))
     :cljs (js->clj (js/JSON.parse (data->characters data charset)))))

(defn deserialize
  "Converts data containing a JSON to cloud event."
  [data & [charset]]
  {:pre [(satisfies? Data data) (or (nil? charset) (string? charset))]}
  (let [js-obj (data->obj data charset)
        js-field->clj-field (js-field->clj-field-by-version (js-obj "specversion"))
        rf (fn [event [js-field js-value]]
             (if-let [clj-field (js-field->clj-field js-field)]
               (let [deser-fn (field->deser-fn js-field (fn [x & _] x))]
                 (if (= clj-field ::transient)
                   event
                   (assoc event clj-field (deser-fn js-value js-obj))))
               (assoc-in event [:ce/extensions (keyword js-field)] js-value)))]
    (reduce rf {} js-obj)))

(defn serialize
  "Converts cloud event to data containing JSON."
  [event & [charset]]
  #?(:clj  nil
     :cljs nil))