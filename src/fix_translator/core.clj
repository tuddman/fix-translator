(ns fix-translator.core
  (:require (cheshire [core :as c])
            (clj-time [core :as t] [format :as f])))

(def codecs (atom {}))

(defn invert-map [m]
  ; Switches the role of keys and values in a map.
  (let [new-keys (vals m)
        new-vals (keys m)]
    (zipmap new-keys new-vals)))

(defn checksum
  ; Returns a 3-character string (left-padded with zeroes) representing the
  ; checksum of msg calculated according to the FIX protocol.
  [msg]
  (format "%03d" (mod (reduce + (.getBytes msg)) 256)))

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
        (println "Error: " (.getMessage e))))))
