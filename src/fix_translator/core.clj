(ns fix-translator.core
  (:require (cheshire [core :as c])
            (clojure [string :as s])
            (clj-time [core :as t] [format :as f])))

(def codecs (atom {}))

(def ^:const tag-delimiter "\u0001")
(def ^:const msg-type-tag "35")
(def ^:const tag-number first)
(def ^:const tag-name first)
(def ^:const translation-fn second)

(defn invert-map [m]
  ; Switches the role of keys and values in a map.
  (let [new-keys (vals m)
        new-vals (keys m)]
    (zipmap new-keys new-vals)))

(defn gen-transformations [tag-spec]
  (if-let [instruction (:transform-by tag-spec)]
    (case instruction
      "by-value" (if-let [values (:values tag-spec)]
                  {:outbound #(values %)
                   :inbound  #((invert-map values) %)}
                  (throw (Exception.
                    (str "For tag " (:tag tag-spec) " in spec, no values found
                      for transform-by-value function"))))
      "to-int"    {:outbound #(str (int %))
                   :inbound  #(Integer/parseInt %)}
      "to-double" {:outbound #(str (double %))
                   :inbound  #(Double/parseDouble %)}
      "to-string" {:outbound #(identity %)
                   :inbound  #(identity %)}
      (throw (Exception.
                    (str "For tag " (:tag tag-spec) " in spec, invalid
                          transform-by function: " instruction))))
    (throw (Exception.
                    (str "For tag " (:tag tag-spec) " in spec, no transform-by
                          function found")))))

(defn gen-codec [tag-name tag-spec]
  (let [transformer (gen-transformations tag-spec)]
    {:encoder {tag-name [(:tag tag-spec) (:outbound transformer)]}
     :decoder {(:tag tag-spec) [tag-name (:inbound transformer)]}}))

(defn load-spec [venue]
  (if (nil? (venue @codecs))
    (let [spec-file (str "specs/" (name venue) ".spec")]
      (try
        (if-let [spec (c/parse-string (slurp spec-file) true)]
          (do
            (swap! codecs assoc venue {:encoder {} :decoder {}
                                       :tags-of-interest {}})
            (doall (for [[k v] (:spec spec)]
              (let [t (gen-codec k v)]
                (swap! codecs update-in [venue :encoder] conj (:encoder t))
                (swap! codecs update-in [venue :decoder] conj (:decoder t)))))
            (swap! codecs assoc-in [venue :tags-of-interest]
                                   (:tags-of-interest spec))
            true))
      (catch Exception e
        (println "Error:" (.getMessage e))
        (swap! codecs dissoc venue)
        nil)))
      true))

(defn get-encoder [venue]
  (if-let [encoder (get-in @codecs [venue :encoder])]
    encoder
    (throw (Exception. (str "No encoder found for " venue ". Have you loaded
      it with load-spec?")))))

(defn translate-to-fix [encoder tag-value]
  (if-let [translator (encoder (first tag-value))]
    (if-let [value ((translation-fn translator) (second tag-value))]
      (str (tag-number translator) "=" value tag-delimiter)
      (throw (Exception. (str "No transformation found for "
                         (second tag-value)))))
    (throw (Exception. (str "tag " (first tag-value) " not found")))))

(defn add-msg-cap [encoder msg]
  (let [msg-length (count msg)
        msg-cap (s/join "" (map (partial translate-to-fix encoder)
                     [[:begin-string :version]
                      [:body-length msg-length]]))]
    (str msg-cap msg)))

(defn checksum
  ; Returns a 3-character string (left-padded with zeroes) representing the
  ; checksum of msg calculated according to the FIX protocol.
  [msg]
  (format "%03d" (mod (reduce + (.getBytes msg)) 256)))

(defn add-checksum [encoder msg]
  (let [chksum (translate-to-fix encoder [:checksum (checksum msg)])]
    (str msg chksum)))

(defn encode-msg [venue tags-values]
  (let [encoder (get-encoder venue)]
    (->> (partition 2 tags-values)
         (map (partial translate-to-fix encoder))
         (s/join "")
         (add-msg-cap encoder)
         (add-checksum encoder))))

(defn get-decoder [venue]
  (if-let [decoder (get-in @codecs [venue :decoder])]
    decoder
    (throw (Exception. (str "No decoder found for " venue ". Have you loaded
      it with load-spec?")))))

(defn get-tags-of-interest [venue msg-type]
  (if-let [tags (get-in @codecs [venue :tags-of-interest msg-type])]
    tags
    (throw (Exception. (str "No venue or tags of interest found for this tag: "
                            msg-type)))))

(defn extract-tag-value 
  ; Extracts the value of a tag from a message.
  [tag msg]
  (let [pattern (re-pattern (str "(?<=" tag "=)(.*?)(?=" tag-delimiter ")"))]
    (peek (re-find pattern msg))))

(defn get-msg-type [venue msg]
  (let [decoder (get-decoder venue)
        msg-type (extract-tag-value msg-type-tag msg)]
    (if-let [msg-type ((translation-fn (decoder msg-type-tag)) msg-type)]
      msg-type
      :unknown-msg-type)))

(defn translate-to-map [decoder tag-value]
  (if-let [translator (decoder (first tag-value))]
    (if-let [value ((translation-fn translator) (second tag-value))]
      {(tag-name translator) value}
    (throw (Exception. (str "No translation found for tag " (first tag-value)
                            " with value " (second tag-value)))))
  (throw (Exception. (str "No decoder found for tag " (first tag-value)
                          " in spec")))))

(defn decode-tag [venue tag msg]
  (let [tag-number (first (get-in @codecs [venue :encoder tag]))
        tag-value (extract-tag-value tag-number msg)]
    ((second (get-in @codecs [venue :decoder tag-number])) tag-value)))

(defn decode-msg [venue msg-type msg]
  (let [decoder (get-decoder venue)
        tags (get-tags-of-interest venue msg-type) 
        pattern (re-pattern (str "(?<=" tag-delimiter ")(" tags ")=(.*?)"
                                 tag-delimiter))]
    (->> (re-seq pattern msg)
         (map #(drop 1 %))
         (map (partial translate-to-map decoder))
         (into {}))))
