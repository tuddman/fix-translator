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

; Add error-checking for failing to find :values in a "by-value" :transform-by.
(defn gen-transformations [tag-spec]
  (let [instruction (:transform-by tag-spec)]
    (case instruction
      "by-value"  {:outbound #((:values tag-spec) %)
                   :inbound  #((invert-map (:values tag-spec)) %)}
      "to-int"    {:outbound #(str (int %))
                   :inbound  #(Integer/parseInt %)}
      "to-double" {:outbound #(str (double %))
                   :inbound  #(Double/parseDouble %)}
      "to-string" {:outbound #(identity %)
                   :inbound  #(identity %)})))

(defn gen-codec [tag-name tag-spec]
  (let [transformer (gen-transformations tag-spec)]
    {:encoder {tag-name [(:tag tag-spec) (:outbound transformer)]}
     :decoder {(:tag tag-spec) [tag-name (:inbound transformer)]}}))

(defn load-spec [venue]
  (if (nil? (venue @codecs))
    (let [spec-file (str "src/fix_translator/specs/" (name venue) ".spec")]
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
        (println "Error:" (.getMessage e)))))
      true))

(defn get-encoder [venue]
  (get-in @codecs [venue :encoder]))

(defn get-decoder [venue]
  (get-in @codecs [venue :decoder]))

(defn get-tags-of-interest [venue msg-type]
  (get-in @codecs [venue :tags-of-interest msg-type]))

(defn checksum
  ; Returns a 3-character string (left-padded with zeroes) representing the
  ; checksum of msg calculated according to the FIX protocol.
  [msg]
  (format "%03d" (mod (reduce + (.getBytes msg)) 256)))

; Must handle cases where the tag is not found (leading to a nil translator)
; and the case where the value is not found (leading to a nil transform)
; must switch this to look more like decode and translate-to-map.
(defn translate-to-fix [encoder tags-values]
  (s/join "" 
    (doall
      (for [[t v] (partition 2 tags-values)]
        (let [translator (t encoder)]
          (if-let [value ((translation-fn translator) v)]
            (str (tag-number translator) "=" v tag-delimiter)
            ()))))))

(defn add-msg-cap [encoder msg]
  (let [msg-length (count msg)
        msg-cap (translate-to-fix encoder [:begin-string :version
                                           :body-length msg-length])]
    (str msg-cap msg)))

(defn add-checksum [encoder msg]
  (let [chksum (translate-to-fix encoder [:checksum (checksum msg)])]
    (str msg chksum)))

(defn encode-msg [venue tags-values]
  (let [encoder (get-in @codecs [venue :encoder])
        translated-msg (translate-to-fix encoder tags-values)]
    (->> translated-msg
         (add-msg-cap encoder)
         (add-checksum encoder))))

(defn extract-tag-value 
  ; Extracts the value of a tag from a message.
  [tag msg]
  (let [pattern (re-pattern (str "(?<=" tag "=)(.*?)(?=" tag-delimiter ")"))]
    (peek (re-find pattern msg))))

(defn get-msg-type [venue msg]
  (let [decoder (get-decoder venue)
        msg-type (extract-tag-value msg-type-tag msg)]
    ((translation-fn (decoder msg-type-tag)) msg-type)))

(defn translate-to-map [decoder tag-value]
  (let [translator (decoder (first tag-value))]
    {(tag-name translator) ((translation-fn translator) (second tag-value))}))

(defn decode-msg [venue msg-type msg]
  (let [decoder (get-decoder venue)
        tags (get-tags-of-interest venue msg-type) 
        pattern (re-pattern (str "(?<=" tag-delimiter ")(" tags ")=(.*?)"
                                 tag-delimiter))]
    (->> (re-seq pattern msg)
         (map #(drop 1 %))
         (map (partial translate-to-map decoder))
         (into {}))))
