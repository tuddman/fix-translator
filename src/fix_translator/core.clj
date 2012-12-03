(ns fix-translator.core
  (:require (cheshire.core :as c)))

(def codecs (atom {}))

(defn invert-map [m]
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

(defn load-spec [venue])


