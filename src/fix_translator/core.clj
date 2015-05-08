(ns fix-translator.core
  (:gen-class)
  (:require (cheshire [core :as c])
            (clojure [string :as s])
            (clj-time [core :as t] [format :as f])))

(def codecs (atom {}))

(def ^:const tag-delimiter "\u0001")
(def ^:const msg-type-tag "35")
(def ^:const tag-number first)
(def ^:const tag-name first)
(def ^:const translation-fn second)

(defn invert-map
  "Switches the role of keys and values in a map."
  [m]
  (let [new-keys (vals m)
        new-vals (keys m)]
    (zipmap new-keys new-vals)))

(defn gen-transformations 
  "Takes a specification for a single FIX tag and returns a map containing
   two transformation functions for that tag: one which transforms a
   spec-neutral value into a valid FIX value (ex: transforms :hearbeat into
   \"0\" for the msg-type tag) and another which transforms a valid FIX value
   into a spec-neutral format (ex: transforms \"2\" into :filled for the
   order-status tag)."
  [tag-spec venue]
  (let [key-format (get-in @codecs [venue :key-format])
        tag (key-format :tag)
        transform-by (key-format :transform-by)
        values (key-format :values)]
    (if-let [instruction (tag-spec transform-by)]
      (case instruction
        "by-value" (if-let [values (tag-spec values)]
                    {:outbound #(values %)
                     :inbound  #((invert-map values) %)}
                    (throw (Exception.
                      (str "For tag " (tag-spec tag) " in spec, no values found
                        for transform-by-value function"))))
        "to-int"    {:outbound #(str (int %))
                     :inbound  #(Integer/parseInt %)}
        "to-double" {:outbound #(str (double %))
                     :inbound  #(Double/parseDouble %)}
        "to-string" {:outbound #(identity %)
                     :inbound  #(identity %)}
        (throw (Exception.
                      (str "For tag " (tag-spec tag) " in spec, invalid
                            transform-by function: " instruction))))
      (throw (Exception.
                      (str "For tag " (tag-spec tag) " in spec, no transform-by
                            function found"))))))

(defn gen-codec 
  "Takes information about a tag and creates an encoding and decoding map for
   it. The encoding map keys a spec-neutral tag name to its corresponding FIX
   tag number and transformation function. The decoding map keys a FIX tag
   number to is tag name and transformation function."
  [tag-name tag-spec venue]
  (let [transformer (gen-transformations tag-spec venue)
        key-format (get-in @codecs [venue :key-format])
        tag (key-format :tag)]
    {:encoder {tag-name [(tag-spec tag) (:outbound transformer)]}
     :decoder {(tag-spec tag) [tag-name (:inbound transformer)]}}))

(defn load-spec
  "Takes a venue name, reads its FIX specification, and generates encoders and
   decoders for each of its tags. The user may specify whether the encoding and
   decoding maps use keyword keys or string keys."
  ([venue]
    (load-spec venue true))
  ([venue use-keyword-keys?]
    (if (nil? (venue @codecs))
      (let [spec-file (str "specs/" (name venue) ".spec")
            key-format (if use-keyword-keys? #(keyword %) #(name %))]
        (try
          (if-let [spec (c/parse-string (slurp spec-file) use-keyword-keys?)]
            (do
              (swap! codecs assoc venue {:encoder {} :decoder {}
                                         :tags-of-interest {}
                                         :key-format key-format})
              (doall (for [[k v] (spec (key-format :spec))]
                (let [t (gen-codec k v venue)]
                  (swap! codecs update-in [venue :encoder] conj (:encoder t))
                  (swap! codecs update-in [venue :decoder] conj (:decoder t)))))
              (swap! codecs assoc-in [venue :tags-of-interest]
                                     (spec (key-format :tags-of-interest)))
              true))
        (catch Exception e
          (println "Error:" (.getMessage e))
          (swap! codecs dissoc venue)
          nil)))
        true)))

(defn get-encoder 
  "Returns the encoder for a particular venue."
  [venue]
  (if-let [encoder (get-in @codecs [venue :encoder])]
    encoder
    (throw (Exception. (str "No encoder found for " venue ". Have you loaded
      it with load-spec?")))))

(defn translate-to-fix 
  "Takes an encoder and a collection containing a spec-neutral tag and its
   value, looks up the corresponding FIX values for the tag and value, and
   returns them formatted as a FIX message fragment."
  [encoder tag-value]
  (if-let [translator (encoder (first tag-value))]
    (if-let [value ((translation-fn translator) (second tag-value))]
      (str (tag-number translator) "=" value tag-delimiter)
      (throw (Exception. (str "No transformation found for "
                         (second tag-value)))))
    (throw (Exception. (str "tag " (first tag-value) " not found")))))

(defn add-msg-cap
  "Takes an encoder and a FIX msg, and prepends it with the FIX version and
   the msg's length."
  [encoder msg]
  (let [msg-length (count msg)
        msg-cap (s/join "" (map (partial translate-to-fix encoder)
                     [[:begin-string :version]
                      [:body-length msg-length]]))]
    (str msg-cap msg)))

(defn checksum
  "Returns a 3-character string (left-padded with zeroes) representing the
   checksum of msg calculated according to the FIX protocol."
  [msg]
  (format "%03d" (mod (reduce + (.getBytes msg)) 256)))

(defn add-checksum 
  "Takes an encoder and a FIX msg, and appends it with the checksum."
  [encoder msg]
  (let [chksum (translate-to-fix encoder [:checksum (checksum msg)])]
    (str msg chksum)))

(defn encode-msg 
  "Takes a venue and a collection of tags and their values in the form [t0 v0
   t1 v1 t2 v2 ...] and transforms it into a FIX message."
  [venue tags-values]
  (let [encoder (get-encoder venue)]
    (->> (partition 2 tags-values)
         (map (partial translate-to-fix encoder))
         (s/join "")
         (add-msg-cap encoder)
         (add-checksum encoder))))

(defn get-decoder
  "Returns the decoder for a particular venue."
  [venue]
  (if-let [decoder (get-in @codecs [venue :decoder])]
    decoder
    (throw (Exception. (str "No decoder found for " venue ". Have you loaded
      it with load-spec?")))))

(defn get-tags-of-interest
  "Takes the venue and FIX message type, and returns the set of tags to be
   extracted from a FIX message and transformed into a spec-neutral format."
  [venue msg-type]
  (if-let [tags (get-in @codecs [venue :tags-of-interest msg-type])]
    tags
    (throw (Exception. (str "No venue or tags of interest found for this tag: "
                            msg-type)))))

(defn extract-tag-value 
  "Extracts the value of a tag from a message."
  [tag msg]
  (let [pattern (re-pattern (str "(?<=" tag "=)(.*?)(?=" tag-delimiter ")"))]
    (peek (re-find pattern msg))))

(defn get-msg-type
  "Returns the FIX message type in spec-neutral form."
  [venue msg]
  (let [decoder (get-decoder venue)
        msg-type (extract-tag-value msg-type-tag msg)]
    (if-let [msg-type ((translation-fn (decoder msg-type-tag)) msg-type)]
      msg-type
      :unknown-msg-type)))

(defn translate-to-map
  "Takes a decoder and a collection with a FIX message tag and its value, and
   returns a map containing the tag name and value in spec-neutral form."
  [decoder tag-value]
  (if-let [translator (decoder (first tag-value))]
    (if-let [value ((translation-fn translator) (second tag-value))]
      {(tag-name translator) value}
    (throw (Exception. (str "No translation found for tag " (first tag-value)
                            " with value " (second tag-value)))))
  (throw (Exception. (str "No decoder found for tag " (first tag-value)
                          " in spec")))))

(defn decode-tag
  "Takes a tag in spec-neutral form and a raw FIX message, extracts the tag's
   value from the message, and returns the value in spec-neutral form."
  [venue tag msg]
  (if-let [tag-number (tag-number (get-in @codecs [venue :encoder tag]))]
    (if-let [decoded-tag-value ((translation-fn
                                (get-in @codecs [venue :decoder tag-number]))
                                (extract-tag-value tag-number msg))]
      decoded-tag-value
      (throw (Exception. (str "No decoding found for " tag))))
    (throw (Exception. (str "No tag number found for " tag)))))

(defn decode-msg
  "Takes a venue, message-type, and a raw FIX message, and returns a map
   containing tags and their values in spec-neutral form."
  [venue msg-type msg]
  (let [decoder (get-decoder venue)
        tags (get-tags-of-interest venue msg-type) 
        pattern (re-pattern (str "(?<=" tag-delimiter ")(" tags ")=(.*?)"
                                 tag-delimiter))]
    (->> (re-seq pattern msg)
         (map #(drop 1 %))
         (map (partial translate-to-map decoder))
         (into {}))))
