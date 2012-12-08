(ns fix-translator.test.core
  (:use [fix-translator.core])
  (:use [clojure.test]))

(deftest invert-map-t
  (let [m {:a 1 :b 2 :c 3}]
    (is (= {1 :a 2 :b 3 :c} (invert-map m)))))

(deftest load-spec-t
  (is (= true (load-spec :test-market)))
  (is (= nil (load-spec :non-market))))

(deftest gen-transformations-t
  (let [_ (load-spec :test-market)
        transform-by-value (gen-transformations {:tag "35"
                                                 :transform-by "by-value"
                                                 :values {:heartbeat "0" 
                                                          :test-request "1"}})]
    (is (= "0" ((:outbound transform-by-value) :heartbeat)))
    (is (= :heartbeat ((:inbound transform-by-value) "0")))

    (is (= "1" ((:outbound transform-by-value) :test-request)))
    (is (= :test-request ((:inbound transform-by-value) "1")))

  (let [transform-to-int (gen-transformations {:tag "38"
                                               :transform-by "to-int"})]
    (is (= "100" ((:outbound transform-to-int) 100)))
    (is (= 100 ((:inbound transform-to-int) "100")))
    
    (is (= "100" ((:outbound transform-to-int) 100.0)))
    (is (= "100" ((:outbound transform-to-int) 100.1)))
    
    (is (thrown? Exception ((:inbound transform-to-int) "100.0"))))

  (let [transform-to-double (gen-transformations {:tag "44"
                                                  :transform-by "to-double"})]
    (is (= "1.0" ((:outbound transform-to-double) 1.0)))
    (is (= "1.0" ((:outbound transform-to-double) 1.00)))
    (is (= 1.0 ((:inbound transform-to-double) "1.00")))

    (is (= "1.01" ((:outbound transform-to-double) 1.01)))
    (is (= 1.01 ((:inbound transform-to-double) "1.01")))

    (is (= "1.0" ((:outbound transform-to-double) 1)))
    (is (= 1.0 ((:inbound transform-to-double) "1"))))

  (let [transform-to-string (gen-transformations {:tag "55"
                                                  :transform-by "to-string"})]
    (is (= "NESNz" ((:outbound transform-to-string) "NESNz")))
    (is (= "NESNz" ((:inbound transform-to-string) "NESNz"))))))

(deftest gen-codec-t)

(deftest checksum-t
  (let [msg-a "checksum-without-delimiter"
        msg-b "checksum\u0001with\u0001delimiters\u0001"]
    (is (= "128" (checksum msg-a)))
    (is (= "068" (checksum msg-b)))))

(deftest message-encoding)

(deftest message-decoding)




