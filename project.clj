(defproject fix-translator "1.06-SNAPSHOT"
  :description "A library to translate FIX messages into maps and vice versa."
  :url "https://github.com/nitinpunjabi/fix-translator"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [cheshire "5.4.0"]
                 [clj-time "0.9.0"]]
  :main fix-translator.core
  :aot [fix-translator.core])
