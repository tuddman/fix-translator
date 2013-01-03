(ns fix-translator.test.core
  (:use [fix-translator.core])
  (:use [clojure.test]))

(deftest invert-map-t
  (let [m {:a 1 :b 2 :c 3}]
    (is (= {1 :a 2 :b 3 :c} (invert-map m)))))

(deftest gen-transformations-t
  (let [_ (load-spec :test-market)
        transform-by-value (gen-transformations {:tag "35"
                                                 :transform-by "by-value"
                                                 :values {:heartbeat "0" 
                                                          :test-request "1"}}
                                                :test-market)]
    (is (= "0" ((:outbound transform-by-value) :heartbeat)))
    (is (= :heartbeat ((:inbound transform-by-value) "0")))

    (is (= "1" ((:outbound transform-by-value) :test-request)))
    (is (= :test-request ((:inbound transform-by-value) "1")))

  (let [transform-to-int (gen-transformations {:tag "38"
                                               :transform-by "to-int"}
                                              :test-market)]
    (is (= "100" ((:outbound transform-to-int) 100)))
    (is (= 100 ((:inbound transform-to-int) "100")))
    
    (is (= "100" ((:outbound transform-to-int) 100.0)))
    (is (= "100" ((:outbound transform-to-int) 100.1)))
    
    (is (thrown? Exception ((:inbound transform-to-int) "100.0"))))

  (let [transform-to-double (gen-transformations {:tag "44"
                                                  :transform-by "to-double"}
                                                 :test-market)]
    (is (= "1.0" ((:outbound transform-to-double) 1.0)))
    (is (= "1.0" ((:outbound transform-to-double) 1.00)))
    (is (= 1.0 ((:inbound transform-to-double) "1.00")))

    (is (= "1.01" ((:outbound transform-to-double) 1.01)))
    (is (= 1.01 ((:inbound transform-to-double) "1.01")))

    (is (= "1.0" ((:outbound transform-to-double) 1)))
    (is (= 1.0 ((:inbound transform-to-double) "1"))))

  (let [transform-to-string (gen-transformations {:tag "55"
                                                  :transform-by "to-string"}
                                                 :test-market)]
    (is (= "NESNz" ((:outbound transform-to-string) "NESNz")))
    (is (= "NESNz" ((:inbound transform-to-string) "NESNz"))))

  (let [invalid-transform {:tag "00" :transform-by "to-nothing"}]
    (is (thrown? Exception (gen-transformations invalid-transform))))

  (let [no-transform-fn {:tag "00"}]
    (is (thrown? Exception (gen-transformations no-transform-fn))))

  (let [no-values {:tag "00" :transform-by "by-value"}]
    (is (thrown? Exception (gen-transformations no-values))))))

(deftest gen-codec-t
  (let [_ (load-spec :test-market)
        fix-tag-name :exec-inst
        tag-spec {:tag "18"
                  :transform-by "by-value"
                  :values {
                    :market-peg "P"
                    :primary-peg "R"
                    :mid-price-peg "M"}}
        codec (gen-codec fix-tag-name tag-spec :test-market)]
    (is (= (tag-number (get-in codec [:encoder :exec-inst])) "18"))
    (is (= ((translation-fn (get-in codec [:encoder fix-tag-name])) :market-peg)
           "P"))
    (is (= ((translation-fn (get-in codec [:encoder fix-tag-name]))
              :primary-peg) "R"))
    (is (= ((translation-fn (get-in codec [:encoder fix-tag-name]))
              :mid-price-peg) "M"))

    (is (= (tag-name (get-in codec [:decoder "18"])) fix-tag-name))
    (is (= ((translation-fn (get-in codec [:decoder "18"])) "P")
           :market-peg))
    (is (= ((translation-fn (get-in codec [:decoder "18"])) "R")
           :primary-peg))
    (is (= ((translation-fn (get-in codec [:decoder "18"])) "M")
           :mid-price-peg))))

(deftest load-spec-t
  (is (= true (load-spec :test-market)))
  (is (= nil (load-spec :non-market))))

(deftest get-encoder-t
  (let [_ (load-spec :test-market)
        encoder (get-encoder :test-market)]
    (is (= (tag-number (encoder :msg-type)) "35"))
    (is (= ((translation-fn (encoder :msg-type)) :heartbeat) "0"))

    (is (thrown? Exception (get-encoder :invalid-market)))))

(deftest translate-to-fix-t
  (let [_ (load-spec :test-market)
        encoder (get-encoder :test-market)]
    (is (= (translate-to-fix encoder [:msg-type :heartbeat])
           (str "35=0" tag-delimiter)))
    (is (not= (translate-to-fix encoder [:msg-type :heartbeat])
           (str "35=0")))
    (is (thrown? Exception
        ((translate-to-fix encoder [:invalid-tag :heartbeat]))))
    (is (thrown? Exception
        ((translate-to-fix encoder [:msg-type :invalid-value]))))))

(deftest add-msg-cap-t
  (let [_ (load-spec :test-market)
        encoder (get-encoder :test-market)]
    (is (= "8=FIX.4.2\u00019=8\u0001my-order"
           (add-msg-cap encoder "my-order")))
    (is (= "8=FIX.4.2\u00019=9\u0001my-order\u0001"
           (add-msg-cap encoder "my-order\u0001")))))

(deftest checksum-t
  (let [msg-a "checksum-without-delimiter"
        msg-b "checksum\u0001with\u0001delimiters\u0001"]
    (is (= "128" (checksum msg-a)))
    (is (= "068" (checksum msg-b)))))

(deftest add-checksum-t
  (let [_ (load-spec :test-market)
        encoder (get-encoder :test-market)
        msg-a "checksum-without-delimiter"
        msg-b "checksum\u0001with\u0001delimiters\u0001"]
    (is (= "checksum-without-delimiter10=128\u0001"
           (add-checksum encoder msg-a)))
    (is (= "checksum\u0001with\u0001delimiters\u000110=068\u0001"
           (add-checksum encoder msg-b)))))

(deftest encode-msg-t
  (let [_ (load-spec :test-market)
        msg [:msg-type :new-order-single :side :buy :order-qty 100
             :symbol "NESNz" :price 1.00]]
    (is (= (str "8=FIX.4.2\u0001" "9=33\u0001" "35=D\u0001" "54=1\u0001"
                "38=100\u0001" "55=NESNz\u0001" "44=1.0\u0001" "10=131\u0001")
           (encode-msg :test-market msg)))))

(deftest get-decoder-t
  (let [_ (load-spec :test-market)
        decoder (get-decoder :test-market)]
    (is (= (tag-name (decoder "35")) :msg-type))
    (is (= ((translation-fn (decoder "35")) "0") :heartbeat))
    (is (thrown? Exception (get-decoder :invalid-market)))))

(deftest get-tags-of-interest-t
  (let [_ (load-spec :test-market)]
    (is (= "45|58|371|372|373" (get-tags-of-interest :test-market :reject)))
    (is (thrown? Exception (get-tags-of-interest :invalid-market :reject)))
    (is (thrown? Exception (get-tags-of-interest :test-market :invalid-tag)))))

(deftest extract-tag-value-t
  (is (= "D" (extract-tag-value "35" "35=D\u0001")))
  (is (= "D" (extract-tag-value "35" "35=D\u000144=1.0\u000155=NESNz\u0001")))
  (is (= "1.0" (extract-tag-value "44" "35=D\u000144=1.0\u000155=NESNz\u0001")))
  (is (= "NESNz" (extract-tag-value "55"
                                    "35=D\u000144=1.0\u000155=NESNz\u0001")))
  (is (= nil (extract-tag-value "35" "35=D")))
  (is (= nil (extract-tag-value "35" "35=D44=1.0")))
  (is (= "1.0" (extract-tag-value "44" "35=D44=1.0\u0001"))))

(deftest get-msg-type-t
  (is (= :logon (get-msg-type :test-market "35=A\u0001")))
  (is (= :test-request (get-msg-type :test-market "35=1\u0001")))
  (is (= :execution-report (get-msg-type :test-market "35=8\u0001")))
  (is (= :unknown-msg-type (get-msg-type :test-market "35=X\u0001"))))

(deftest translate-to-map-t
  (let [_ (load-spec :test-market)
        decoder (get-decoder :test-market)]
    (is (= (translate-to-map decoder ["35" "8"])
           {:msg-type :execution-report}))
    (is (thrown? Exception (translate-to-map decoder ["00" "8"])))
    (is (thrown? Exception (translate-to-map decoder ["35" "Z"])))))

(deftest decode-tag-t
  (let [_ (load-spec :test-market)
        msg "35=8\u000144=1.0\u000155=NESNz\u000139=0\u0001"]
    (is (= :execution-report (decode-tag :test-market :msg-type msg)))
    (is (= 1.0 (decode-tag :test-market :price msg)))
    (is (= "NESNz" (decode-tag :test-market :symbol msg)))
    (is (= :new (decode-tag :test-market :order-status msg)))))

(deftest decode-msg-t
  (let [_ (load-spec :test-market)]
    (is (= {:price 1.0 :symbol "NESNz" :order-status :new}
           (decode-msg :test-market :execution-report
                       "35=8\u000144=1.0\u000155=NESNz\u000139=0\u0001")))
    (is (thrown? Exception (decode-msg :test-market :execution-report
                           "35=8\u000144=1.0\u000155=NESNz\u000139=Z\u0001")))))



