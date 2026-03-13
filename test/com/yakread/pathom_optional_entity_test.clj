(ns com.yakread.pathom-optional-entity-test
  "Minimal standalone reproduction of a Pathom3 behavior where a pass-through
   resolver's input declarations determine resolver execution order and which
   fields are visible to downstream resolvers.

   Real-world scenario (yakread favorite icon bug):
   - `user-item` batch resolver provides {:item/user-item [:user-item/id
     :user-item/favorited-at]} given an item with :item/id
   - `from-params` resolver takes `params/item-unsafe` (which includes nested
     :item/user-item) and passes it through as `params/item`
   - `from-params` INPUT declaration determines what Pathom resolves for the
     nested entity BEFORE passing it through

   Bug: from-params INPUT only declares {:item/user-item [:user-item/id]}.
   Pathom resolves the batch resolver for :user-item/id but NOT for
   :user-item/favorited-at (since it's not declared as needed). The optional
   field never makes it through to downstream resolvers.

   This file demonstrates three approaches:

   1. BUGGY: authorize input declares nested entity with partial fields.
      Pathom runs customer-loader BEFORE authorize (wrong order), and
      filters out :customer/email from the input (wrong data).

   2. COUPLED-FIX: authorize input declares all needed nested fields.
      Works, but authorize must know about all downstream needs.

   3. MINIMAL-INPUT: authorize only needs :order/id. Does its own auth
      check without depending on Pathom to resolve nested entities.
      Pathom resolves nested entities AFTER authorize runs.
      Authorize runs first (correct security order) and doesn't need
      to know about downstream resolver needs (no coupling)."
  (:require [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.interface.eql :as p.eql]))

;; Track which resolvers are called and in what order
(def call-log (atom []))

;; --- Simulated database ---
(def customer-emails
  {1 "alice@example.com"
   2 "bob@example.com"})

;; --- Resolver 1: "order-lookup" ---
;; Provides the basic order entity. Analogous to yakread's URL param lookup
;; that creates params/item-unsafe from the item ID in the URL.
(defresolver order-lookup [env input]
  {::pco/output [{:params/order-unsafe [:order/id]}]}
  (swap! call-log conj :order-lookup)
  {:params/order-unsafe {:order/id 1}})

;; --- Resolver 2: "customer-loader" ---
;; A resolver that can provide :order/customer given :order/id.
;; Analogous to yakread's `user-item` batch resolver that fetches from the DB.
(defresolver customer-loader [env {:keys [order/id]}]
  {::pco/input [:order/id]
   ::pco/output [{:order/customer [:customer/id :customer/email]}]}
  (swap! call-log conj :customer-loader)
  {:order/customer {:customer/id id
                    :customer/email (get customer-emails id)}})

;; --- Approach 1: BUGGY authorize ---
;; INPUT only declares it needs [:customer/id] from the nested entity.
;; Pathom runs customer-loader BEFORE authorize, and filters out :customer/email.
(defresolver authorize-buggy [env {:keys [params/order-unsafe]}]
  {::pco/input [{:params/order-unsafe [:order/id
                                       {(? :order/customer) [:customer/id]}]}]
   ::pco/output [{:params/order [:order/id
                                 {:order/customer [:customer/id]}]}]}
  (swap! call-log conj :authorize-buggy)
  {:params/order order-unsafe})

;; --- Approach 2: COUPLED-FIX authorize ---
;; INPUT declares all downstream fields. Works, but couples authorize
;; to all downstream resolver needs.
(defresolver authorize-coupled-fix [env {:keys [params/order-unsafe]}]
  {::pco/input [{:params/order-unsafe [:order/id
                                       {(? :order/customer) [:customer/id
                                                             (? :customer/email)]}]}]
   ::pco/output [{:params/order [:order/id
                                 {:order/customer [:customer/id
                                                   :customer/email]}]}]}
  (swap! call-log conj :authorize-coupled-fix)
  {:params/order order-unsafe})

;; --- Approach 3: MINIMAL-INPUT authorize ---
;; INPUT only requires :order/id. Does its own auth check (simulated here
;; with the customer-emails lookup). Outputs only {:order/id ...}.
;; Pathom resolves :order/customer AFTER authorize, within the params/order
;; entity context. No coupling, correct ordering.
(defresolver authorize-minimal [env {:keys [params/order-unsafe]}]
  {::pco/input [{:params/order-unsafe [:order/id]}]
   ::pco/output [{:params/order [:order/id]}]}
  (swap! call-log conj :authorize-minimal)
  ;; Simulated auth check: verify order exists (like yakread's SQL auth query)
  (let [order-id (:order/id order-unsafe)]
    (when (contains? customer-emails order-id)
      {:params/order {:order/id order-id}})))

;; --- Resolver 4: "email-display" ---
;; Downstream resolver that needs :customer/email.
;; Analogous to yakread's `like-button` resolver.
(defresolver email-display [env {:keys [order/customer]}]
  {::pco/input [{:order/customer [(? :customer/email)]}]
   ::pco/output [:order/email-display]}
  (swap! call-log conj :email-display)
  {:order/email-display (or (:customer/email customer) "<missing>")})

(defn run-test [label authorize-resolver]
  (reset! call-log [])
  (let [env (pci/register [order-lookup authorize-resolver customer-loader email-display])
        query [{:params/order [:order/email-display]}]
        result (try
                 (p.eql/process env query)
                 (catch Exception e
                   {:error (.getMessage e)}))]
    (println (str "--- " label " ---"))
    (println "  Resolver call order:" @call-log)
    (let [display (get-in result [:params/order :order/email-display])]
      (println "  :order/email-display =" (pr-str display))
      (println "  Expected: \"alice@example.com\"")
      (let [log @call-log
            auth-idx (first (keep-indexed (fn [i v] (when (#{:authorize-buggy :authorize-coupled-fix :authorize-minimal} v) i)) log))
            loader-idx (first (keep-indexed (fn [i v] (when (= :customer-loader v) i)) log))]
        (println "  Authorize runs before customer-loader?"
                 (if (and auth-idx loader-idx)
                   (< auth-idx loader-idx)
                   "N/A")))
      (println "  Correct result?" (= "alice@example.com" display)))
    (println)))

(defn -main []
  (println "=== Pathom3: Pass-through Resolver Ordering & Input Filtering ===\n")

  (run-test "APPROACH 1 - BUGGY: nested input with partial fields"
            authorize-buggy)

  (run-test "APPROACH 2 - COUPLED-FIX: nested input with ALL downstream fields"
            authorize-coupled-fix)

  (run-test "APPROACH 3 - MINIMAL-INPUT: only :order/id, auth internally"
            authorize-minimal)

  (println "=== Summary ===")
  (println "")
  (println "Approach 1 (BUGGY): authorize runs AFTER customer-loader, and")
  (println "  the input shape filters out :customer/email. Both wrong.")
  (println "")
  (println "Approach 2 (COUPLED-FIX): works, but authorize must declare every")
  (println "  field that any downstream resolver might need. Defeats the")
  (println "  purpose of using Pathom for decoupled resolution.")
  (println "")
  (println "Approach 3 (MINIMAL-INPUT): authorize only needs :order/id.")
  (println "  It runs BEFORE customer-loader (correct security order).")
  (println "  After authorize provides {:params/order {:order/id 1}},")
  (println "  Pathom resolves :order/customer within params/order for")
  (println "  downstream resolvers. No coupling, correct ordering."))




