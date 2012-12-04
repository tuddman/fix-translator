(ns fix-translator.core
  (:require (cheshire [core :as c])
            (clojure [string :as s])
            (clj-time [core :as t] [format :as f])))

(def codecs (atom {}))

(def ^:const tag-delimiter "\u0001")
(def ^:const tag-number first)
(def ^:const translation-fn second)

(defn invert-map [m]
  ; Switches the role of keys and values in a map.
  (let [new-keys (vals m)
        new-vals (keys m)]
    (zipmap new-keys new-vals)))

(defn gen-transformations [tag-spec]
  (let [instruction (:transform-by tag-spec)]
    (case instruction
      "by-value"  {:outbound #((:values tag-spec) %)
                   :inbound  #((invert-map (:values tag-spec)) %)}
      "to-int"    {:outbound #(str %)
                   :inbound  #(Integer/parseInt %)}
      "to-double" {:outbound #(str %)
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
                                       :tags-of-interest ""})
            (doall (for [[k v] (:spec spec)]
              (let [t (gen-codec k v)]
                (swap! codecs update-in [venue :encoder] conj (:encoder t))
                (swap! codecs update-in [venue :decoder] conj (:decoder t)))))
            (swap! codecs assoc-in [venue :tags-of-interest]
                                   (:tags-of-interest spec))
            true))
      (catch Exception e
        (println "Error: " (.getMessage e)))))
      true))

(defn checksum
  ; Returns a 3-character string (left-padded with zeroes) representing the
  ; checksum of msg calculated according to the FIX protocol.
  [msg]
  (format "%03d" (mod (reduce + (.getBytes msg)) 256)))

(defn translate-to-fix [encoder tags-values]
  (s/join "" 
    (doall
      (for [[t v] tags-values]
        (let [translator (t encoder)]
          (str (tag-number translator) "="
                ((translation-fn translator) v) tag-delimiter))))))

(defn add-msg-cap [encoder msg]
  (let [msg-length (count msg)
        msg-cap (translate-to-fix encoder {:begin-string :version
                                           :body-length msg-length})]
    (str msg-cap msg)))

(defn add-checksum [encoder msg]
  (let [chksum (translate-to-fix encoder {:checksum (checksum msg)})]
    (str msg chksum)))

(defn encode-msg [venue tags-values]
  (let [encoder (get-in @codecs [venue :encoder])
        translated-msg (s/join "" (translate-to-fix encoder tags-values))]
    (->> translated-msg
         (add-msg-cap encoder)
         (add-checksum encoder))))
        








