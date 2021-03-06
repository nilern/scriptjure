;;; scriptjure -- a library for generating javascript from Clojure s-exprs

;; by Allen Rohner, http://arohner.blogspot.com
;;                  http://www.reasonr.com  
;; October 7, 2009

;; Copyright (c) Allen Rohner, 2009. All rights reserved.  The use
;; and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution.  By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license.  You must not
;; remove this notice, or any other, from this software.

;; This library generates javascript from Clojure s-exprs. To use it, 
;; (js (fn foo [x] (var x (+ 3 5)) (return x)))
;;  returns a string, "function foo (x) { var x = (3 + 5); return x; }"
;;
;; See the README and the tests for more information on what is supported.
;; 
;; The library is intended to generate javascript glue code in Clojure
;; webapps. One day it might become useful enough to write entirely
;; JS libraries in clojure, but it's not there yet.
;;
;;

(ns #^{:author "Allen Rohner"
       :doc "A library for generating javascript from Clojure."}
       com.reasonr.scriptjure
       (:require [clojure.string :as str])
       (:require [com.reasonr.string :as rstr])
       (:use clojure.walk))

(defn- throwf [& message]
  (throw (Exception. (apply format message))))

(defmulti emit (fn [ expr ] (type expr)))

(defmulti emit-special (fn [ & args] (first args)))

(def statement-separator ";\n")

(defn statement [expr]
  (if (not (= statement-separator (rstr/tail (count statement-separator) expr)))
    (str expr statement-separator)
    expr))

(defn comma-list [coll]
  (str "(" (str/join ", " coll) ")"))

(defmethod emit nil [expr]
  "null")

(defmethod emit java.lang.Integer [expr]
  (str expr))

(defmethod emit clojure.lang.Ratio [expr]
  (str (float expr)))

(defmethod emit java.lang.String [^String expr]
  (str \" (.replace expr "\"" "\\\"") \"))

(defn valid-symbol? [sym]
  ;;; This is incomplete, it disallows unicode
  (boolean (re-matches #"[_$\p{Alpha}][.\w]*" (str sym))))

(defmethod emit clojure.lang.Keyword [expr]
  (when-not (valid-symbol? (name expr))
    (#'throwf "%s is not a valid javascript symbol" expr))
  (str (name expr)))

(defmethod emit clojure.lang.Symbol [expr]
  (when-not (valid-symbol? (str expr))
    (#' throwf "%s is not a valid javascript symbol" expr))
  (str expr))

(defmethod emit java.util.regex.Pattern [expr]
  (str \/ expr \/))

(defmethod emit :default [expr]
  (str expr))

(def special-forms (set ['var '. '.. 'if 'funcall 'fn 'function 'quote 'set! 'return 'delete 'new 'do 'aget 'while 'doseq 'str 'inc! 'dec! 'dec 'inc 'defined? 'and 'or '? 'try 'break]))

(def prefix-unary-operators (set ['!]))

(def suffix-unary-operators (set ['++ '--]))

(def infix-operators (set ['+ '+= '- '-= '/ '* '% '== '=== '< '> '<= '>= '!=
                           '<< '>> '<<< '>>> '!== '& '| '&& '|| '= 'not= 'instanceof]))

(def chainable-infix-operators (set ['+ '- '* '/ '& '| '&& '||]))


(defn special-form? [expr]
  (contains? special-forms expr))


(defn infix-operator? [expr]
  (contains? infix-operators expr))

(defn prefix-unary? [expr]
  (contains? prefix-unary-operators expr))

(defn suffix-unary? [expr]
  (contains? suffix-unary-operators expr))

(defn emit-prefix-unary [type [operator arg]]
  (str operator (emit arg)))

(defn emit-suffix-unary [type [operator arg]]
  (str (emit arg) operator))

(defn emit-infix [type [operator & args]]
  (when (and (not (chainable-infix-operators operator)) (> (count args) 2))
    (throw (Exception. (str "operator " operator " supports only 2 arguments"))))
  (let [substitutions {'= '=== '!= '!== 'not= '!==}]
    (str "(" (str/join (str " " (or (substitutions operator) operator) " ")
                       (map emit args)) ")")))

(def ^{:dynamic true} var-declarations nil)

(defmacro with-var-declarations [& body]
  `(binding [var-declarations (atom [])]
     ~@body))

(defmethod emit-special 'var [type [var & more]]
  (apply swap! var-declarations conj (filter identity (map (fn [name i] (when (odd? i) name)) more (iterate inc 1))))
  (apply str (interleave (map (fn [[name expr]]
                                (str (when-not var-declarations "var ") (emit name) " = " (emit expr)))
                              (partition 2 more))
                         (repeat statement-separator))))

(defmethod emit-special 'funcall [type [name & args]]
  (str (if (and (list? name) (= 'fn (first name))) ; function literal call
         (str "(" (emit name) ")")
         (emit name))
       (comma-list (map emit args))))

(defmethod emit-special 'str [type [str & args]]
  (apply clojure.core/str (interpose " + " (map emit args))))

(defn emit-method [obj method args]
  (str (emit obj) "." (emit method) (comma-list (map emit args))))

(defmethod emit-special '. [type [period obj method & args]]
  (emit-method obj method args))

(defmethod emit-special '.. [type [dotdot & args]]
  (apply str (interpose "." (map emit args))))

(defmethod emit-special 'if [type [if test true-form & false-form]]
  (str "if (" (emit test) ") { \n"
       (emit true-form)
       "\n }"
       (when (first false-form)
         (str " else { \n"
              (emit (first false-form))
              "\n }"))))
       
(defmethod emit-special 'dot-method [type [method obj & args]]
  (let [method (symbol (rstr/drop 1 (str method)))]
    (emit-method obj method args)))

(defmethod emit-special 'return [type [return expr]]
  (statement (str "return " (emit expr))))

(defmethod emit-special 'delete [type [return expr]]
  (str "delete " (emit expr)))

(defmethod emit-special 'set! [type [set! var val & more]]
  (assert (or (nil? more) (even? (count more))))
  (str (emit var) " = " (emit val) statement-separator
       (if more (str (emit (cons 'set! more))))))

(defmethod emit-special 'new [type [new class & args]]
  (str "new " (emit class) (comma-list (map emit args))))

(defmethod emit-special 'aget [type [aget var & idxs]]
  (apply str
         (emit var)
         (interleave (repeat "[") (map emit idxs) (repeat "]"))))

(defmethod emit-special 'inc! [type [inc var]]
  (str (emit var) "++"))

(defmethod emit-special 'dec! [type [dec var]]
  (str (emit var) "--"))

(defmethod emit-special 'dec [type [_ var]]
  (str "(" (emit var) " - " 1 ")"))

(defmethod emit-special 'inc [type [_ var]]
  (str "(" (emit var) " + " 1 ")"))

(defmethod emit-special 'defined? [type [_ var]]
  (str "typeof " (emit var) " !== \"undefined\" && " (emit var) " !== null"))

(defmethod emit-special '? [type [_ test then else]]
  (str (emit test) " ? " (emit then) " : " (emit else)))

(defmethod emit-special 'and [type [_ & more]]
  (apply str (interpose "&&" (map emit more))))

(defmethod emit-special 'or [type [_ & more]]
  (apply str (interpose "||" (map emit more))))

(defmethod emit-special 'quote [type [_ & more]]
  (apply str more))

(defn emit-do [exprs]
  (str/join "" (map (comp statement emit) exprs)))

(defmethod emit-special 'do [type [ do & exprs]]
  (emit-do exprs))

(defmethod emit-special 'while [type [while test & body]]
  (str "while (" (emit test) ") { \n"
       (emit-do body)
       "\n }"))

(defmethod emit-special 'doseq [type [doseq bindings & body]]
  (str "for (" (emit (first bindings)) " in " (emit (second bindings)) ") { \n"
       (if-let [more (nnext bindings)]
         (emit (list* 'doseq more body))
         (emit-do body))
       "\n }"))

(defn emit-var-declarations []
  (when-not (empty? @var-declarations)
    (apply str "var "
           (str/join ", " (map emit @var-declarations))
           statement-separator)))

(defn emit-function
  ([name sig body declaration]
   (assert (or (symbol? name) (nil? name)))
   (assert (vector? sig))
   (with-var-declarations
     (let [body (emit-do body)]
       (str "function " (when declaration name) (comma-list sig) " {\n"
            (emit-var-declarations) body " }"))))
  ([name sig body] (emit-function name sig body nil)))

(defmethod emit-special 'fn [type [fn & expr]]
  (let [name (when (symbol? (first expr)) (first expr))]
    (when name
      (swap! var-declarations conj name))
    (if name
      (let [signature (second expr)
            body (rest (rest expr))]
        (str name " = " (emit-function name signature body)))
      (let [signature (first expr)
            body (rest expr)]
        (str (emit-function nil signature body))))))

(defmethod emit-special 'function [type [fn & expr]]
  (let [name (first expr), signature (second expr), body (rest (rest expr))]
    (emit-function name signature body :declaration)))

(defmethod emit-special 'try [type [try & body :as expression]]
  (let [try-body (remove #(contains? #{'catch 'finally} (first %))
                         body)
        catch-clause (filter #(= 'catch (first %))
                             body)
        finally-clause (filter #(= 'finally (first %))
                               body)]
    (cond
     (and (empty? catch-clause)
          (empty? finally-clause))
     (throw (new Exception (str "Must supply a catch or finally clause (or both) in a try statement! " expression)))

     (> (count catch-clause) 1)
     (throw (new Exception (str "Multiple catch clauses in a try statement are not currently supported! " expression)))

     (> (count finally-clause) 1)
     (throw (new Exception (str "Cannot supply more than one finally clause in a try statement! " expression)))

     :true (str "try{\n"
                (emit-do try-body)
                "}\n"
                (if-let [[_ exception & catch-body] (first catch-clause)]
                  (str "catch(" (emit exception) "){\n"
                       (emit-do catch-body)
                       "}\n"))
                (if-let [[_ & finally-body] (first finally-clause)]
                  (str "finally{\n"
                       (emit-do finally-body)
                       "}\n"))))))

(defmethod emit-special 'break [type [break]]
  (statement "break"))

(declare emit-custom custom-form?)

(derive clojure.lang.Cons ::list)
(derive clojure.lang.IPersistentList ::list)

(defmethod emit ::list [expr]
  (if (symbol? (first expr))
    (let [head (symbol (name (first expr))) ; remove any ns resolution
          expr (conj (rest expr) head)]
      (cond
       (and (= (rstr/get (str head) 0) \.)
            (> (count (str head)) 1)

            (not (= (rstr/get (str head) 1) \.))) (emit-special 'dot-method expr)
        (custom-form? head) (emit-custom head expr)
       (special-form? head) (emit-special head expr)
       (infix-operator? head) (emit-infix head expr)
        (prefix-unary? head) (emit-prefix-unary head expr)
        (suffix-unary? head) (emit-suffix-unary head expr)
       :else (emit-special 'funcall expr)))
    (if (list? expr)
      (emit-special 'funcall expr)
      (throw (new Exception (str "invalid form: " expr))))))

(defmethod emit clojure.lang.IPersistentVector [expr]
  (str "[" (str/join ", " (map emit expr)) "]"))

(defmethod emit clojure.lang.LazySeq [expr]
  (emit (into [] expr)))

(defmethod emit clojure.lang.IPersistentMap [expr]
  (letfn [(json-pair [pair] (str (emit (key pair)) ": " (emit (val pair))))]
    (str "{" (str/join ", " (map json-pair (seq expr))) "}")))

(defn _js [forms]
  (with-var-declarations
       (let [code (if (> (count forms) 1)
                    (emit-do forms)
                    (emit (first forms)))]
         ;;(println "js " forms " => " code)
         (str (emit-var-declarations) code))))

(defn- unquote?
  "Tests whether the form is (unquote ...)."
  [form]
  (and (seq? form) (symbol? (first form)) (= (symbol (name (first form))) 'clj)))

(defn handle-unquote [form]
  (second form))

(declare inner-walk outer-walk)

(defn- inner-walk [form]
  (cond 
   (unquote? form) (handle-unquote form)
   :else (walk inner-walk outer-walk form)))

(defn- outer-walk [form]
  (cond
    (symbol? form) (list 'quote form)
    (seq? form) (list* 'list form)
    :else form))

(defmacro quasiquote [form]
  (let [post-form (walk inner-walk outer-walk form)]
    post-form))

(defmacro js*
  "returns a fragment of 'uncompiled' javascript. Compile to a string using js."
  [& forms]
  (if (= (count forms) 1)
    `(quasiquote ~(first forms))
    (let [do-form `(do ~@forms)]
      `(quasiquote ~do-form))))

(defmacro cljs*
  "equivalent to (js* (clj form))"
  [form]
  `(js* (~'clj ~form)))

(defmacro cljs
  "equivalent to (js (clj form))"
  [form]
  `(js (clj ~form)))

(defmacro js 
  "takes one or more forms. Returns a string of the forms translated into javascript"
  [& forms]
  `(_js (quasiquote ~forms)))


;;**********************************************************
;; Custom forms
;;**********************************************************

(defonce custom-forms (atom {}))

(defmacro defjsmacro [nme params & body]
  `(do
     (defn ~nme ~params
       (js*
         ~@body))
     (add-custom-form '~nme ~nme)))

(defn add-custom-form [form func]
  (swap! custom-forms assoc form func))

(defn get-custom [form]
  (get @custom-forms form))

(defn custom-form? [expr]
  (get-custom expr))

(defn emit-custom [head expr]
  (when-let [func (get-custom head)]
    (let [v (apply func (next expr))]
      (emit v))))

