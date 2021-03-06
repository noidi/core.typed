(ns clojure.core.typed.type-ctors
  (:refer-clojure :exclude [defrecord])
  (:require [clojure.core.typed
             [utils :as u]
             [type-rep :as r :refer [TCType]]
             [filter-rep :as fr]
             [rclass-env :as rcls]
             [cs-rep :as crep]
             [util-vars :as vs]
             [fold-rep :as f]
             [name-env :as nme-env]
             [datatype-env :as dtenv]]
            [clojure.core.typed :as t]
            [clojure.math.combinatorics :as comb]
            [clojure.set :as set]
            [clojure.reflect :as reflect])
  (:import (clojure.core.typed.type_rep HeterogeneousMap Poly TypeFn PolyDots TApp App Value
                                        Union Intersection F Function Mu B KwArgs KwArgsSeq RClass
                                        Bounds Name Scope CountRange)
           (clojure.lang Seqable IPersistentSet IPersistentMap Symbol Keyword)))

(t/typed-deps clojure.core.typed.utils
              clojure.core.typed.type-rep
              clojure.core.typed.cs-rep
              clojure.core.typed.name-env)

; create an alias cr -> cs-rep
(t/tc-ignore
  (create-ns 'clojure.core.typed.cs-rep)
  (alias 'cr 'clojure.core.typed.cs-rep)
  )

(declare Un)

;; Heterogeneous maps

(t/ann ^:nocheck -hmap (Fn [(Seqable TCType) -> TCType]
                           [(Seqable TCType) Boolean -> TCType]
                           [(Seqable TCType) (IPersistentSet TCType) Boolean -> TCType]))
(defn -hmap 
  ([types] (-hmap types #{} true))
  ([types other-keys?] (-hmap types #{} other-keys?))
  ([types absent-keys other-keys?]
   (if (some #{(Un)} (concat (keys types) (vals types)))
     (Un)
     (r/->HeterogeneousMap types absent-keys other-keys?))))

(t/ann -complete-hmap [(Seqable TCType) -> TCType])
(defn -complete-hmap [types]
  (-hmap types false))

(t/def-alias TypeMap
  "A regular map with types as keys and vals."
  (IPersistentMap TCType TCType))

(t/ann ^:nocheck make-HMap (Fn [TypeMap TypeMap -> TCType]
                               [TypeMap TypeMap Any -> TCType]))
(defn make-HMap 
  "Generate a type which is every possible combination of mandatory
  and optional key entries. Takes an optional third parameter which
  is true if the entries are complete (ie. we know there are no more entries),
  and false otherwise. Defaults to false."
  ([mandatory optional]
   (make-HMap mandatory optional false))
  ([mandatory optional complete?]
   (assert (= #{}
              (set/intersection (-> mandatory keys set)
                                (-> optional keys set))))
   (apply Un
          (for [ss (map #(into {} %) (comb/subsets optional))]
            (-hmap (merge mandatory ss) 
                   ;other optional keys cannot appear...
                   (set/difference (set (keys optional))
                                   (set (keys ss)))
                   ;...but we don't know about other keys
                   (not complete?))))))

;TODO to type check this, need to un-munge instance field names
(t/ann ^:nocheck complete-hmap? [HeterogeneousMap -> Any])
(defn complete-hmap? [^HeterogeneousMap hmap]
  {:pre [(r/HeterogeneousMap? hmap)]}
  (not (.other-keys? hmap)))

;; Unions

#_(defn simplify-HMap-Un [hmaps]
  {:pre [(every? r/HeterogeneousMap? hmaps)]
   :post [(r/Type? %)]}
  (let [mss (vals
              (group-by #(-> % :types keys set) (set hmaps)))
        ;union the vals of maps with exactly the same keys
        flat (set
               (for [ms mss]
                 (-hmap
                   (apply merge-with Un
                          (map :types ms)))))]
    (if (= 1 (count flat))
      (first flat)
      (->Union flat))))

(t/ann ^:nocheck Un [TCType * -> TCType])
(defn Un [& types]
  (let [types (disj (set types) r/empty-union)]
    (cond
      (empty? types) r/empty-union
      (= 1 (count types)) (first types)
      :else (r/->Union (set (apply concat
                                   (for [t (set types)]
                                     (if (r/Union? t)
                                       (:types t)
                                       [t]))))))))

;; Intersections

(declare overlap)

(t/ann ^:nocheck In [TCType * -> TCType])
(defn In [& types]
  {:pre [(every? r/Type? types)]
   :post [(r/Type? %)]}
           ;flatten intersections
  (let [ts (set (apply concat
                       (for [t (set types)]
                         (if (r/Intersection? t)
                           (:types t)
                           [t]))))]
    (cond
      (or (empty? ts)
          (ts (Un))) (Un)

      (= 1 (count ts)) (first ts)

      ; if there no overlap
      (and (<= (count ts) 2)
           (some (fn [[t1 t2]] (not (overlap t1 t2))) (comb/combinations ts 2))) (Un)

      (some r/Union? ts) (let [flat (set (mapcat #(if (r/Union? %)
                                                    (:types %)
                                                    [%])
                                                 ts))]
                           (apply Un
                                  (set
                                    (for [[t1 t2] (comb/combinations flat 2)]
                                      (In t1 t2)))))

      (ts r/-any) (apply In (disj ts r/-any))
      :else (r/->Intersection ts))))

;; RClass

(t/ann *current-RClass-super* Symbol)
(def ^:dynamic *current-RClass-super*)

(declare Poly* instantiate-poly)

;smart constructor
(t/ann ^:nocheck RClass* 
  (Fn [(Seqable Symbol) (Seqable r/Variance) (Seqable TCType) Symbol (IPersistentMap Symbol TCType) -> TCType]
      [(Seqable Symbol) (Seqable r/Variance) (Seqable TCType) Symbol 
       (IPersistentMap Symbol TCType) (IPersistentSet TCType) -> TCType]))
(defn RClass* 
  ([names variances poly? the-class replacements]
   (RClass* names variances poly? the-class replacements #{}))
  ([names variances poly? the-class replacements unchecked-ancestors]
  {:pre [(every? symbol? names)
         (every? r/variance? variances)
         (= (count variances) (count poly?))
         (every? r/Type? poly?)
         (symbol? the-class)]}
  (if (seq variances)
    (Poly* names (repeat (count names) r/no-bounds) (r/->RClass variances poly? the-class replacements unchecked-ancestors) names)
    (r/->RClass nil nil the-class replacements unchecked-ancestors))))

(t/ann ^:nocheck isa-DataType? [(U Symbol Class) -> Any])
(defn isa-DataType? [sym-or-cls]
  {:pre [((some-fn symbol? class?) sym-or-cls)]}
  (let [cls (if (class? sym-or-cls)
              sym-or-cls
              (u/symbol->Class sym-or-cls))]
    (isa? cls clojure.lang.IType)))

(t/ann ^:nocheck isa-Record? [(U Symbol Class) -> Any])
(defn isa-Record? [sym-or-cls]
  {:pre [((some-fn symbol? class?) sym-or-cls)]}
  (let [cls (if (class? sym-or-cls)
              sym-or-cls
              (u/symbol->Class sym-or-cls))]
    (isa? cls clojure.lang.IRecord)))

(t/ann ^:nocheck RClass-of (Fn [(U Symbol Class) -> TCType]
                               [(U Symbol Class) (U nil (Seqable TCType)) -> TCType]))
(defn RClass-of 
  ([sym-or-cls] (RClass-of sym-or-cls nil))
  ([sym-or-cls args]
   {:pre [((some-fn class? symbol?) sym-or-cls)
          (every? r/Type? args)]
    :post [((some-fn r/RClass? r/DataType?) %)]}
   (let [sym (if (class? sym-or-cls)
               (u/Class->symbol sym-or-cls)
               sym-or-cls)
         rc ((some-fn dtenv/get-datatype rcls/get-rclass) 
             sym)]
     (assert ((some-fn r/Poly? r/RClass? r/DataType? nil?) rc))
     (assert (or (r/Poly? rc) (empty? args))
             (str "Cannot instantiate non-polymorphic RClass " sym
                  (when *current-RClass-super*
                    (str " when checking supertypes of RClass " *current-RClass-super*))))
     (cond 
       (r/Poly? rc) (instantiate-poly rc args)
       ((some-fn r/DataType? r/RClass?) rc) rc
       :else
       (let [cls (u/symbol->Class sym)]
         (if (isa-DataType? cls)
           (r/->DataType sym nil nil [] (isa-Record? cls))
           (r/->RClass nil nil sym {} #{})))))))

(t/ann ^:nocheck RClass-of-with-unknown-params [(U Symbol Class) -> TCType])
(defn RClass-of-with-unknown-params
  ([sym-or-cls]
   {:pre [((some-fn class? symbol?) sym-or-cls)]
    :post [((some-fn r/RClass? r/DataType?) %)]}
   (let [sym (if (class? sym-or-cls)
               (u/Class->symbol sym-or-cls)
               sym-or-cls)
         rc ((some-fn dtenv/get-datatype rcls/get-rclass) sym)
         args (when (r/Poly? rc)
                ;instantiate with Any, could be more general if respecting variance
                (repeat (.nbound ^Poly rc) r/-any))]
     (RClass-of sym args))))

;TODO won't type check because records+destructuring
(t/ann ^:nocheck RClass-supers* [RClass -> (Seqable TCType)])
(defn RClass-supers* 
  "Return a set of ancestors to the RClass"
  [{:keys [poly? replacements the-class unchecked-ancestors] :as rcls}]
  {:pre [(r/RClass? rcls)]
   :post [(set? %)
          (every? r/Type? %)
          (<= (count (filter (some-fn r/FnIntersection? r/Poly? r/PolyDots?) %))
              1)]}
  (let [;set of symbols of Classes we haven't explicitly replaced
        not-replaced (set/difference (set (map u/Class->symbol (-> the-class u/symbol->Class supers)))
                                     (set (keys replacements)))]
    (set/union (binding [*current-RClass-super* the-class]
                 (set (doall 
                        (for [csym not-replaced]
                          (RClass-of-with-unknown-params csym)))))
               (set (vals replacements))
               #{(RClass-of Object)}
               unchecked-ancestors)))

;; TypeFn

(declare abstract-many instantiate-many)

;smart constructor
(t/ann ^:nocheck TypeFn* [(Seqable Symbol) (Seqable r/Variance) (Seqable Bounds) TCType -> TCType])
(defn TypeFn* [names variances bbnds body]
  {:pre [(every? symbol names)
         (every? r/variance? variances)
         (every? r/Bounds? bbnds)
         (apply = (map count [names variances bbnds]))
         ((some-fn r/TypeFn? r/Type?) body)]
   :post [(r/Type? %)]}
  (if (empty? names)
    body
    (r/->TypeFn (count names) 
                variances
                (vec
                  (for [bnd bbnds]
                    (r/visit-bounds bnd #(abstract-many names %))))
                (abstract-many names body))))

;smart destructor
(t/ann ^:nocheck TypeFn-body* [(Seqable Symbol) TypeFn -> TCType])
(defn TypeFn-body* [names ^TypeFn typefn]
  {:pre [(every? symbol? names)
         (r/TypeFn? typefn)]}
  (assert (= (.nbound typefn) (count names)) "Wrong number of names")
  (instantiate-many names (.scope typefn)))

(t/ann ^:nocheck TypeFn-bbnds* [(Seqable Symbol) TypeFn -> (Seqable Bounds)])
(defn TypeFn-bbnds* [names ^TypeFn typefn]
  {:pre [(every? symbol? names)
         (r/TypeFn? typefn)]
   :post [(every? r/Bounds? %)]}
  (assert (= (.nbound typefn) (count names)) "Wrong number of names")
  (mapv (fn [b]
          (r/visit-bounds b #(instantiate-many names %)))
        (.bbnds typefn)))

;; Poly

;smart constructor
(t/ann ^:nocheck Poly* [(Seqable Symbol) (Seqable Bounds) TCType (Seqable Symbol) -> TCType])
(defn Poly* [names bbnds body free-names]
  {:pre [(every? symbol names)
         (every? r/Bounds? bbnds)
         (r/Type? body)
         (every? symbol? free-names)
         (apply = (map count [names bbnds free-names]))]}
  (if (empty? names)
    body
    (r/->Poly (count names) 
              (vec
                (for [bnd bbnds]
                  (r/visit-bounds bnd #(abstract-many names %))))
              (abstract-many names body)
              free-names)))

(t/ann ^:nocheck Poly-free-names* [Poly -> (Seqable Symbol)])
(defn Poly-free-names* [^Poly poly]
  {:pre [(r/Poly? poly)]
   :post [((every-pred seq (u/every-c? symbol?)) %)]}
  (.actual-frees poly))

;smart destructor
(t/ann ^:nocheck Poly-body* [(Seqable Symbol) Poly -> TCType])
(defn Poly-body* [names ^Poly poly]
  {:pre [(every? symbol? names)
         (r/Poly? poly)]}
  (assert (= (.nbound poly) (count names)) "Wrong number of names")
  (instantiate-many names (.scope poly)))

(t/ann ^:nocheck Poly-bbnds* [(Seqable Symbol) Poly -> (Seqable Bounds)])
(defn Poly-bbnds* [names ^Poly poly]
  {:pre [(every? symbol? names)
         (r/Poly? poly)]}
  (assert (= (.nbound poly) (count names)) "Wrong number of names")
  (mapv (fn [b]
          (r/visit-bounds b #(instantiate-many names %)))
        (.bbnds poly)))

;; PolyDots

;smart constructor
(t/ann ^:nocheck PolyDots* [(Seqable Symbol) (Seqable Bounds) TCType -> TCType])
(defn PolyDots* [names bbnds body]
  {:pre [(every? symbol names)
         (every? r/Bounds? bbnds)
         (r/Type? body)]}
  (assert (= (count names) (count bbnds)) "Wrong number of names")
  (if (empty? names)
    body
    (r/->PolyDots (count names) 
                  (mapv (fn [bnd] 
                          (r/visit-bounds bnd #(abstract-many names %)))
                        bbnds)
                  (abstract-many names body))))

;smart destructor
(t/ann ^:nocheck PolyDots-body* [(Seqable Symbol) PolyDots -> TCType])
(defn PolyDots-body* [names ^PolyDots poly]
  {:pre [(every? symbol? names)
         (r/PolyDots? poly)]}
  (assert (= (.nbound poly) (count names)) "Wrong number of names")
  (instantiate-many names (.scope poly)))

(t/ann ^:nocheck PolyDots-bbnds* [(Seqable Symbol) PolyDots -> (Seqable Bounds)])
(defn PolyDots-bbnds* [names ^PolyDots poly]
  {:pre [(every? symbol? names)
         (r/PolyDots? poly)]}
  (assert (= (.nbound poly) (count names)) "Wrong number of names")
  (mapv (fn [b]
          (r/visit-bounds b #(instantiate-many names %)))
        (.bbnds poly)))


;; Instantiate ops

(t/tc-ignore
(defn- unparse-type-var []
  (let [v (ns-resolve (find-ns 'clojure.core.typed.parse-unparse)
                      'unparse-type)]
    (assert (var? v) "unparse-type unbound")
    v))
  )

(t/ann ^:nocheck make-simple-substitution [(Seqable Symbol) (Seqable TCType) -> cr/SubstMap])
(defn make-simple-substitution [vs ts]
  {:pre [(every? symbol? vs)
         (every? r/Type? ts)
         (= (count vs)
            (count ts))]}
  (into {} (for [[v t] (map vector vs ts)]
             [v (crep/->t-subst t r/no-bounds)])))

(t/tc-ignore
(defn- subst-all-var []
  (let [v (ns-resolve (find-ns 'clojure.core.typed.subst) 'subst-all)]
    (assert (var? v) "subst-all unbound")
    v))
  )

(t/ann ^:nocheck instantiate-typefn [TypeFn (Seqable TCType) -> TCType])
(defn instantiate-typefn [^TypeFn t types]
  (let [subst-all @(subst-all-var)
        unparse-type @(unparse-type-var)]
    (assert (r/TypeFn? t) (str "instantiate-typefn requires a TypeFn: " (unparse-type t)))
    (do (assert (= (.nbound t) (count types)) (u/error-msg "Wrong number of arguments passed to type function: "
                                                         (unparse-type t) (mapv unparse-type types)))
        (let [nms (repeatedly (.nbound t) gensym)
              body (TypeFn-body* nms t)]
          (subst-all (make-simple-substitution nms types) body)))))

(t/ann ^:nocheck instantiate-poly [Poly (Seqable TCType) -> TCType])
(defn instantiate-poly [t types]
  (let [subst-all @(subst-all-var)
        unparse-type @(unparse-type-var)]
    (cond
      (r/Poly? t) (do (assert (= (:nbound t) (count types)) (u/error-msg "Wrong number of arguments (" (count types) 
                                                                       ") passed to polymorphic type: "
                                                                       (unparse-type t)
                                                                       (when (bound? #'*current-RClass-super*)
                                                                         (str " when checking ancestors of " *current-RClass-super*))))
                      (let [nms (repeatedly (:nbound t) gensym)
                            body (Poly-body* nms t)]
                        (subst-all (make-simple-substitution nms types) body)))
      ;PolyDots NYI
      :else (throw (Exception. "instantiate-poly: requires Poly, and PolyDots NYI")))))

;; Resolve

(declare resolve-tapp* -resolve resolve-app*)

(t/ann ^:nocheck resolve-TApp [TApp -> TCType])
(defn resolve-TApp [^TApp app]
  {:pre [(r/TApp? app)]}
  (resolve-tapp* (.rator app) (.rands app)))

(t/ann ^:nocheck resolve-tapp* [TCType (Seqable TCType) -> TCType])
(defn resolve-tapp* [rator rands]
  (let [unparse-type @(unparse-type-var)
        ^TypeFn rator (-resolve rator)
        _ (assert (r/TypeFn? rator) (unparse-type rator))]
    (assert (= (count rands) (.nbound rator))
            (u/error-msg "Wrong number of arguments provided to type function"
                         (unparse-type rator)))
    (instantiate-typefn rator rands)))

(t/ann ^:nocheck resolve-App [App -> TCType])
(defn resolve-App [^App app]
  {:pre [(r/App? app)]}
  (resolve-app* (.rator app) (.rands app)))

(t/ann ^:nocheck resolve-app* [TCType (Seqable TCType) -> TCType])
(defn resolve-app* [rator rands]
  (let [unparse-type @(unparse-type-var)
        rator (-resolve rator)]
    (cond
      (r/Poly? rator) (do (assert (= (count rands) (.nbound ^Poly rator))
                                  (u/error-msg "Wrong number of arguments provided to polymorphic type"
                                             (unparse-type rator)))
                          (instantiate-poly rator rands))
      ;PolyDots NYI
      :else (throw (Exception. (str (when vs/*current-env*
                                      (str (:line vs/*current-env*) ": "))
                                    "Cannot apply non-polymorphic type " (unparse-type rator)))))))

(declare resolve-Name unfold)

(t/ann -resolve [TCType -> TCType])
(defn -resolve [ty]
  {:pre [(r/AnyType? ty)]}
  (cond 
    (r/Name? ty) (resolve-Name ty)
    (r/Mu? ty) (unfold ty)
    (r/App? ty) (resolve-App ty)
    (r/TApp? ty) (resolve-TApp ty)
    :else ty))

(t/ann requires-resolving? [TCType -> Any])
(defn requires-resolving? [ty]
  {:pre [(r/Type? ty)]}
  (or (r/Name? ty)
      (r/App? ty)
      (and (r/TApp? ty)
           (not (r/F? (.rator ^TApp ty))))
      (r/Mu? ty)))

(t/ann resolve-Name [Name -> TCType])
(defn resolve-Name [nme]
  {:pre [(r/Name? nme)]}
  (nme-env/resolve-name* (:id nme)))

(t/ann fully-resolve-type 
       (Fn [TCType -> TCType]
           [TCType (IPersistentSet TCType) -> TCType]))
(defn fully-resolve-type 
  ([t seen]
   (let [_ (assert (not (seen t)) "Infinite non-Rec type detected")
         seen (conj seen t)]
     (if (requires-resolving? t)
       (recur (-resolve t) seen)
       t)))
  ([t] (fully-resolve-type t #{})))

;; Mu

(declare abstract instantiate)

;smart constructor
(t/ann Mu* [Symbol TCType -> TCType])
(defn Mu* [name body]
  (r/->Mu (abstract name body)))

;smart destructor
(t/ann Mu-body* [Symbol Mu -> TCType])
(defn Mu-body* [name t]
  {:pre [(r/Mu? t)
         (symbol? name)]}
  (instantiate name (:scope t)))

(t/tc-ignore
(defn- substitute-var []
  (let [v (ns-resolve (find-ns 'clojure.core.typed.subst) 'substitute)]
    (assert (var? v) "substitute unbound")
    v))
  )

(t/ann ^:nocheck unfold [Mu -> TCType])
(defn unfold [t]
  {:pre [(r/Mu? t)]
   :post [(r/Type? %)]}
  (let [substitute @(substitute-var)
        sym (gensym)
        body (Mu-body* sym t)]
    (substitute t sym body)))

;; Utils

(t/ann Value->Class [Value -> Class])
(defn ^Class Value->Class [^Value tval]
  {:post [(class? %)]}
  (class (.val tval)))

(t/ann keyword-value? [Any -> Any])
(defn keyword-value? [^Value val]
  (boolean
    (when (r/Value? val)
      (keyword? (.val val)))))

;; Overlap

;; FIXME much better algorithms around I'm sure
(t/ann ^:nocheck countrange-overlap? [CountRange CountRange -> Any])
(defn countrange-overlap? 
  [{lowerl :lower upperl :upper :as l}
   {lowerr :lower upperr :upper :as r}]
  {:pre [(r/CountRange? l)
         (r/CountRange? r)]}
  (cond 
    (and upperl upperr)
        (or 
          ;; -----
          ;;   -------
          ;; and
          ;;   ---
          ;;   -------
          (<= lowerl lowerr upperl upperr)

          ;;    --
          ;;   -------
          (<= lowerr lowerl upperl upperr)

          ;;     ------
          ;; -------
          ;; and
          ;;     ---
          ;; -------
          (<= lowerr lowerl upperr upperl)

          ;; otherwise no overlap
          false)

    upperl ;; and (not upperr)
      (or 
        ;; ----
        ;;  ----->>
        ;; and
        ;;  ---
        ;;  ----->>
        (<= lowerl lowerr upperl)
        ;;   ---
        ;;  ----->>
        (<= lowerr lowerl)
        ;; otherwise no overlap
        false)
    upperr
      (or
        ;; ------>>
        ;;  ----
        ;; and
        ;;  ----->>
        ;;  ---
        (<= lowerl lowerr)
        
        ;;   --->>
        ;; ----
        (<= lowerr lowerl upperr)

        ;; else no overlap
        false)
    :else ;; (and (not upperl) (not upperr))
    ;; ---->>
    ;;   -->>
    ;; and
    ;;   -->>
    ;; ---->>
    true))

(t/tc-ignore
(defn- subtype?-var []
  (let [v (ns-resolve (find-ns 'clojure.core.typed.subtype) 'subtype?)]
    (assert (var? v) "subtype? unbound")
    v))
  )

;true if types t1 and t2 overlap (NYI)
(t/ann ^:nocheck overlap [TCType TCType -> Any])
(defn overlap [t1 t2]
  (let [subtype? @(subtype?-var)
        t1 (fully-resolve-type t1)
        t2 (fully-resolve-type t2)
        eq (= t1 t2)
        hmap-and-seq? (fn [h s] (and (r/HeterogeneousMap? h)
                                     (r/RClass? s)
                                     (= (u/Class->symbol clojure.lang.ISeq) (:the-class s))))
        hvec-and-seq? (fn [h s] (and (r/HeterogeneousVector? h)
                                     (r/RClass? s)
                                     (= (u/Class->symbol clojure.lang.ISeq) (:the-class s))))]
    (cond 
      eq eq

      (and (r/Value? t1)
           (r/Value? t2))
      eq

      ;if both are Classes, and at least one isn't an interface, then they must be subtypes to have overlap
      (and (r/RClass? t1)
           (r/RClass? t2)
           (let [{t1-flags :flags} (reflect/type-reflect (r/RClass->Class t1))
                 {t2-flags :flags} (reflect/type-reflect (r/RClass->Class t2))]
             (some (complement :interface) [t1-flags t2-flags])))
      (or (subtype? t1 t2)
          (subtype? t2 t1))

      (or (r/Value? t1)
          (r/Value? t2)) 
      (or (subtype? t1 t2)
          (subtype? t2 t1))

      (and (r/CountRange? t1)
           (r/CountRange? t2)) 
      (countrange-overlap? t1 t2)

      (r/Union? t1)
      (boolean 
        (some #(overlap % t2) (.types ^Union t1)))

      (r/Union? t2)
      (boolean 
        (some #(overlap t1 %) (.types ^Union t2)))

      (r/Intersection? t1)
      (every? #(overlap % t2) (.types ^Intersection t1))

      (r/Intersection? t2)
      (every? #(overlap t1 %) (.types ^Intersection t2))

      (and (r/HeterogeneousMap? t1)
           (r/HeterogeneousMap? t2)) 
      (and (= (set (-> t1 :types keys))
              (set (-> t2 :types keys)))
           (every? true?
                   (for [[k1 v1] (:types t1)]
                     (let [v2 ((:types t2) k1)]
                       (overlap v1 v2)))))

      ;for map destructuring mexpansion
      (or (hmap-and-seq? t1 t2)
          (hmap-and-seq? t2 t1))
      false

      ;for vector destructuring mexpansion
      (or (hvec-and-seq? t1 t2)
          (hvec-and-seq? t2 t1))
      false

      :else true))) ;FIXME conservative result

(t/tc-ignore
(defn- infer-var []
  (let [v (ns-resolve (find-ns 'clojure.core.typed.cs-gen) 'infer)]
    (assert (var? v) "infer unbound")
    v))
  )

; restrict t1 to be a subtype of t2
(t/ann ^:nocheck restrict [TCType TCType -> TCType])
(defn restrict [t1 t2]
  (let [subtype? @(subtype?-var)
        subst-all @(subst-all-var)
        infer @(infer-var)]
    (cond
      (subtype? t1 t2) t1 ;; already a subtype

      (not (overlap t1 t2)) (Un) ;there's no overlap, so the restriction is empty

      (r/Union? t1) (apply Un (map (fn [e] (restrict e t2)) (:types t1)))
      (r/Union? t2) (apply Un (map (fn [e] (restrict t1 e)) (:types t2)))

      (r/Poly? t2)
      (let [names (repeatedly (:nbound t2) gensym)
            t (Poly-body* names t2)
            bbnds (Poly-bbnds* names t2)
            subst (try 
                    (infer (zipmap names bbnds) {} (list t1) (list t) t1)
                    (catch IllegalArgumentException e
                      (throw e))
                    (catch Exception e))]
        (and subst (restrict t1 (subst-all subst t1))))

      ;TODO other cases
      :else (In t2 t1))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Variable rep

(t/ann ^:nocheck add-scopes [t/AnyInteger TCType -> (U TCType Scope)])
(defn add-scopes 
  "Wrap type in n Scopes"
  [n t]
  {:pre [(u/nat? n)
         (r/Type? t)]
   :post [((some-fn r/Scope? r/Type?) %)]}
  (last 
    (take (inc n) (iterate r/->Scope t))))

(t/ann remove-scopes [t/AnyInteger (U Scope TCType) -> (U Scope TCType)])
(defn remove-scopes 
  "Unwrap n Scopes"
  [n sc]
  {:pre [(u/nat? n)
         (or (zero? n)
             (r/Scope? sc))]
   :post [(or (r/Scope? %) (r/Type? %))]}
  (last
    (take (inc n) (iterate (fn [t]
                             (assert (r/Scope? t) "Tried to remove too many Scopes")
                             (:body t))
                           sc))))

(t/ann ^:nocheck rev-indexed (All [x] [(Seqable x) -> (Seqable '[t/AnyInteger x])]))
(defn- rev-indexed 
  "'(a b c) -> '([2 a] [1 b] [0 c])"
  [c]
  (map vector (iterate dec (dec (count c))) c))

(t/tc-ignore
(derive ::abstract-many f/fold-rhs-default)

(f/add-fold-case ::abstract-many
                 F
                 (fn [{name* :name :as t} {{:keys [name count outer sb]} :locals}]
                   (if (= name name*)
                     (r/->B (+ count outer))
                     t)))

(f/add-fold-case ::abstract-many
                 Function
                 (fn [{:keys [dom rng rest drest kws]} {{:keys [name count outer sb]} :locals}]
                   (r/->Function (doall (map sb dom))
                                 (sb rng)
                                 (when rest (sb rest))
                                 (when drest
                                   (-> drest
                                       (update-in [:pre-type] sb)
                                       (update-in [:name] #(if (= % name)
                                                             (+ count outer)
                                                             %))))
                                 (when kws
                                   (letfn [(abstract-kw-map [m]
                                             {:pre [(map? m)]}
                                             (into {} (for [[k v] m]
                                                        [k (sb v)])))]
                                     (-> kws
                                       (update-in [:mandatory] abstract-kw-map)
                                       (update-in [:optional] abstract-kw-map)))))))

(f/add-fold-case ::abstract-many
                 Mu
                 (fn [{:keys [scope]} {{:keys [name count type outer name-to]} :locals}]
                   (let [body (remove-scopes 1 scope)]
                     (r/->Mu (r/->Scope (name-to name count type (inc outer) body))))))

(f/add-fold-case ::abstract-many
                 PolyDots
                 (fn [{bbnds* :bbnds n :nbound body* :scope} {{:keys [name count type outer name-to]} :locals}]
                   (let [rs #(remove-scopes n %)
                         body (rs body*)
                         bbnds (mapv #(r/visit-bounds % rs) bbnds*)
                         as #(add-scopes n (name-to name count type (+ n outer) %))]
                     (r/->PolyDots n 
                                   (mapv #(r/visit-bounds % rs) bbnds)
                                   (as body)))))

(f/add-fold-case ::abstract-many
                 Poly
                 (fn [{bbnds* :bbnds n :nbound body* :scope :as poly} {{:keys [name count type outer name-to]} :locals}]
                   (let [rs #(remove-scopes n %)
                         body (rs body*)
                         bbnds (mapv #(r/visit-bounds % rs) bbnds*)
                         as #(add-scopes n (name-to name count type (+ n outer) %))]
                     (r/->Poly n 
                             (mapv #(r/visit-bounds % as) bbnds)
                             (as body)
                             (Poly-free-names* poly)))))

(f/add-fold-case ::abstract-many
                 TypeFn
                 (fn [{bbnds* :bbnds n :nbound body* :scope :keys [variances]} {{:keys [name count type outer name-to]} :locals}]
                   (let [rs #(remove-scopes n %)
                         body (rs body*)
                         bbnds (mapv #(r/visit-bounds % rs) bbnds*)
                         as #(add-scopes n (name-to name count type (+ n outer) %))]
                     (r/->TypeFn n 
                                 variances
                                 (mapv #(r/visit-bounds % as) bbnds)
                                 (as body)))))
  )

(t/ann ^:nocheck abstract-many [(Seqable Symbol) TCType -> (U TCType Scope)])
(defn abstract-many 
  "Names Type -> Scope^n  where n is (count names)"
  [names ty]
  {:pre [(every? symbol? names)
         ((some-fn r/Type? r/TypeFn?) ty)]}
  (letfn [(name-to 
            ([name count type] (name-to name count type 0 type))
            ([name count type outer ty]
             (letfn [(sb [t] (name-to name count type outer t))]
               (f/fold-rhs ::abstract-many
                 {:type-rec sb
                  :filter-rec (f/sub-f sb ::abstract-many)
                  :object-rec (f/sub-o sb ::abstract-many)
                  :locals {:name name
                           :count count
                           :outer outer
                           :sb sb
                           :name-to name-to}}
                 ty))))]
    (if (empty? names)
      ty
      (let [n (count names)]
        (loop [ty ty
               names names
               count (dec n)]
          (if (zero? count)
            (add-scopes n (name-to (first names) 0 ty))
            (recur (name-to (first names) count ty)
                   (next names)
                   (dec count))))))))

(t/tc-ignore
(derive ::instantiate-many f/fold-rhs-default)

(f/add-fold-case ::instantiate-many
               B
               (fn [{:keys [idx] :as t} {{:keys [count outer image sb]} :locals}]
                 (if (= (+ count outer) idx)
                   (r/->F image)
                   t)))

(f/add-fold-case ::instantiate-many
               Function
               (fn [{:keys [dom rng rest drest kws]} {{:keys [count outer image sb]} :locals}]
                 (r/->Function (map sb dom)
                             (sb rng)
                             (when rest
                               (sb rest))
                             (when drest
                               (-> drest
                                 (update-in [:pre-type] sb)
                                 (update-in [:name] #(do
                                                       (assert (u/nat? %))
                                                       (if (= (+ count outer) %)
                                                         image
                                                         %)))))
                             (when kws
                               (letfn [(instantiate-kw-map [m]
                                         {:pre [(map? m)]}
                                         (into {} (for [[k v] m]
                                                    [k (sb v)])))]
                                 (-> kws
                                   (update-in [:mandatory] instantiate-kw-map)
                                   (update-in [:optional] instantiate-kw-map)))))))

(f/add-fold-case ::instantiate-many
               Mu
               (fn [{:keys [scope]} {{:keys [replace count outer image sb type]} :locals}]
                 (let [body (remove-scopes 1 scope)]
                   (r/->Mu (r/->Scope (replace image count type (inc outer) body))))))

(f/add-fold-case ::instantiate-many
               PolyDots
               (fn [{bbnds* :bbnds n :nbound body* :scope} {{:keys [replace count outer image sb type]} :locals}]
                 (let [rs #(remove-scopes n %)
                       body (rs body*)
                       bbnds (mapv #(r/visit-bounds % rs) bbnds*)
                       as #(add-scopes n (replace image count type (+ n outer) %))]
                   (r/->PolyDots n 
                               (mapv #(r/visit-bounds % as) bbnds)
                               (as body)))))

(f/add-fold-case ::instantiate-many
               Poly
               (fn [{bbnds* :bbnds n :nbound body* :scope :as poly} {{:keys [replace count outer image sb type]} :locals}]
                 (let [rs #(remove-scopes n %)
                       body (rs body*)
                       bbnds (mapv #(r/visit-bounds % rs) bbnds*)
                       as #(add-scopes n (replace image count type (+ n outer) %))]
                   (r/->Poly n 
                           (mapv #(r/visit-bounds % as) bbnds)
                           (as body)
                           (Poly-free-names* poly)))))

(f/add-fold-case ::instantiate-many
               TypeFn
               (fn [{bbnds* :bbnds n :nbound body* :scope :keys [variances]} {{:keys [replace count outer image sb type]} :locals}]
                 (let [rs #(remove-scopes n %)
                       body (rs body*)
                       bbnds (mapv #(r/visit-bounds % rs) bbnds*)
                       as #(add-scopes n (replace image count type (+ n outer) %))]
                   (r/->TypeFn n 
                             variances
                             (mapv #(r/visit-bounds % as) bbnds)
                             (as body)))))
  )

(t/ann ^:nocheck instantiate-many [(Seqable Symbol) Scope -> TCType])
(defn instantiate-many 
  "instantiate-many : List[Symbols] Scope^n -> Type
  Instantiate de Bruijn indices in sc to frees named by
  images, preserving upper/lower bounds"
  [images sc]
  {:pre [(every? symbol? images)
         (or (r/Scope? sc)
             (empty? images))]
   :post [((some-fn r/Type? r/TypeFn?) %)]}
  (letfn [(replace 
            ([image count type] (replace image count type 0 type))
            ([image count type outer ty]
             (letfn [(sb [t] (replace image count type outer t))]
               (let [sf (f/sub-f sb ::instantiate-many)]
                 (f/fold-rhs ::instantiate-many
                   {:type-rec sb 
                    :filter-rec sf 
                    :object-rec (f/sub-o sb ::instantiate-many)
                    :locals {:count count
                             :outer outer
                             :image image
                             :sb sb
                             :type type
                             :replace replace}}
                   ty)))))]
    (if (empty? images)
      sc
      (let [n (count images)]
        (loop [ty (remove-scopes n sc)
               images images
               count (dec n)]
          (if (zero? count)
            (replace (first images) 0 ty)
            (recur (replace (first images) count ty)
                   (next images)
                   (dec count))))))))

(t/ann abstract [Symbol TCType -> TCType])
(defn abstract [name ty]
  "Make free name bound"
  {:pre [(symbol? name)
         (r/Type? ty)]}
  (abstract-many [name] ty))

(t/ann instantiate [Symbol Scope -> TCType])
(defn instantiate [f sc]
  "Instantiate bound name to free"
  {:pre [(symbol? f)
         (r/Scope? sc)]}
  (instantiate-many [f] sc))

;TODO not sure why this fails to type check
;[(U Any {kw x}) -> (U nil x) :filters {:then (is {kw Any} 0)}]
(t/ann ^:nocheck keyword->Fn [Keyword -> TCType])
(defn keyword->Fn [kw]
  {:pre [(keyword? kw)]
   :post [(r/Type? %)]}
  (Poly* ['x]
         [r/no-bounds]
         (r/make-FnIntersection
           (r/make-Function
             [(Un r/-any (-hmap {(r/-val kw) (r/make-F 'x)}))]
             (Un r/-nil (r/make-F 'x))
             nil nil
             :filter (fr/->FilterSet 
                       (fr/->TypeFilter (-hmap {(r/-val kw) r/-any}) nil 0) 
                       fr/-top)))
         ['x]))

;;; KwArgs

(t/ann KwArgs->Type [KwArgs -> TCType])
(defn KwArgs->Type [^KwArgs kws]
  {:pre [(r/KwArgs? kws)]
   :post [(r/Type? %)]}
  (r/->KwArgsSeq (.mandatory kws)
                 (.optional kws)))

(t/ann KwArgsSeq->HMap [KwArgsSeq -> TCType])
(defn KwArgsSeq->HMap [^KwArgsSeq kws]
  {:pre [(r/KwArgsSeq? kws)]
   :post [(r/Type? %)]}
  (make-HMap (.mandatory kws) (.optional kws)))
