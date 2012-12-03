(ns fix-translator.test.core
  (:use [fix-translator.core])
  (:use [clojure.test]))

(deftest invert-map-t
  (let [m {:a 1 :b 2 :c 3}]
    (is (= {1 :a 2 :b 3 :c} (invert-map m)))))

(deftest load-spec-t
  (is (= true (load-spec :quote-mtf)))
  (is (= nil (load-spec :non-market))))

(deftest message-encoding)

(deftest message-decoding)
