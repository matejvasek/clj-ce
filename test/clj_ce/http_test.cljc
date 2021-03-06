(ns clj-ce.http-test
  (:require [clojure.test :refer [is deftest]]
            [clj-ce.test-data-binary :as bin-data]
            [clj-ce.test-data-structured :as struct-data]
            [clj-ce.http :as ce-http]
            [clj-ce.json :as j])
  #?(:clj (:import (java.io ByteArrayInputStream))))

#?(:cljs
   (extend-protocol IEquiv
     js/goog.Uri
     (-equiv [o other]
       (= (.toString o)
          (.toString other)))))

(def ^:private ext-deser
  {"anumber"  #?(:clj  #(Long/valueOf ^String %)
                 :cljs js/parseInt)
   "aboolean" #?(:clj #(Boolean/valueOf ^String %)
                 :cljs #(= "true" %))
   "astring"  identity})

(def ^:private ext-ser
  {:anumber str
   :aboolean str
   :astring identity})

(deftest binary-http->event-test
  (doseq [arguments bin-data/data]
    (let [{:keys [headers body event]} arguments
          e (ce-http/binary-msg->event {:headers headers :body body} :extensions-fns ext-deser)]
      (is (= event e)))))

(deftest event->binary-http&back-test
  (doseq [arguments bin-data/data]
    (let [{:keys [event]} arguments]
      (is (= event (-> event
                       (ce-http/event->binary-msg :extensions-fns ext-ser)
                       (ce-http/binary-msg->event :extensions-fns ext-deser)))))))


(deftest structured-http->event&back-test-utf8
  (doseq [arguments struct-data/data]
    (let [{:keys [event]} arguments]
      (is (= event (-> event
                       (ce-http/event->structured-msg "json" j/cloudevent->json "utf-8")
                       (ce-http/structured-msg->event {"json" j/json->cloudevent})))))))

(def encodings
  "Body of http message may be of various types and using various charsets.

  This variable contains a collection of triples [name, body-fn, headers-fn],
  where `name` is name of encoding, `body-fn` is a function that transform
  body string into desired object (e.g. java.io.InputStream) and `headers-fn`
  is a function that updates headers accordingly (e.g. it sets charset in
  content-type header)."
  #?(:clj
     [["String"
       identity
       identity]
      ["UTF-8 in bytes"
       (fn [body] (.getBytes ^String body "UTF-8"))
       (fn [headers] (update headers "content-type" #(str % "; charset=UTF-8")))]
      ["ISO-8859-2 in bytes"
       (fn [body] (.getBytes ^String body "ISO-8859-2"))
       (fn [headers] (update headers "content-type" #(str % "; charset=iso-8859-2")))]
      ["UTF-8 in InputStream"
       (fn [body] (ByteArrayInputStream. (.getBytes ^String body "UTF-8")))
       (fn [headers] (update headers "content-type" #(str % "; charset=UTF-8")))]]
     :cljs
     [["String"
       identity
       identity]
      ["UTF-8 in Uint8Array"
       (fn [body] (.encode (js/TextEncoder. "utf-8") body))
       (fn [headers] (update headers "content-type" #(str % "; charset=utf-8")))]
      ["UTF-8 in ArrayBuffer"
       (fn [body] (.-buffer (.encode (js/TextEncoder. "utf-8") body)))
       (fn [headers] (update headers "content-type" #(str % "; charset=utf-8")))]]))

(defn- fix-up-data [event]
  "Converts binary body to string body for sake of testing equality."
  (let [{:ce/keys [data]} event]
    #?(:clj
       (if (instance? (Class/forName "[B") data)
         (update event :ce/data #(String. ^bytes % "utf-8"))
         event)
       :cljs
       (if (instance? js/Uint8Array data)
         (update event :ce/data #(.decode (js/TextDecoder. "UTF-8") %))
         event))))

(deftest structured-http->event-test
  (doseq [[name body-fn headers-fn] encodings]
    (doseq [[idx {:keys [headers body event]}] (map-indexed vector struct-data/data)]
      (let [body (body-fn body)
            headers (headers-fn headers)
            e (ce-http/structured-msg->event {:headers headers :body body}
                                             {"json" j/json->cloudevent})
            e (fix-up-data e)]
        (is (= event e) (str "Test idx: " idx ", with encoding: " name "."))))))
