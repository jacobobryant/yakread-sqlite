(ns com.yakread.pathom-optional-entity-test
  "Minimal standalone reproduction of a Pathom3 behavior where a pass-through
   resolver's input/output declarations determine whether a batch resolver runs.

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

   Fix: Add (? :user-item/favorited-at) to from-params's INPUT and OUTPUT
   declarations. Now Pathom resolves the batch resolver for the additional
   field, and the pass-through includes it."
  (:require [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.interface.eql :as p.eql]))

;; Track which resolvers are called
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

;; --- Resolver 3a: "authorize" (BUGGY) ---
;; Pass-through that transforms params/order-unsafe → params/order.
;; INPUT only declares it needs [:customer/id] from the nested entity.
;; Pathom only resolves customer-loader for :customer/id, not :customer/email.
(defresolver authorize-buggy [env {:keys [params/order-unsafe]}]
  {::pco/input [{:params/order-unsafe [:order/id
                                       {(? :order/customer) [:customer/id]}]}]
   ::pco/output [{:params/order [:order/id
                                 {:order/customer [:customer/id]}]}]}
  (swap! call-log conj :authorize-buggy)
  {:params/order order-unsafe})

;; --- Resolver 3b: "authorize" (FIXED) ---
;; INPUT declares it needs [:customer/id (? :customer/email)] from nested entity.
;; This causes Pathom to resolve customer-loader for :customer/email too.
(defresolver authorize-fixed [env {:keys [params/order-unsafe]}]
  {::pco/input [{:params/order-unsafe [:order/id
                                       {(? :order/customer) [:customer/id
                                                             (? :customer/email)]}]}]
   ::pco/output [{:params/order [:order/id
                                 {:order/customer [:customer/id
                                                   :customer/email]}]}]}
  (swap! call-log conj :authorize-fixed)
  {:params/order order-unsafe})

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
        ;; Query: resolve params/order, then get email-display within it.
        query [{:params/order [:order/email-display]}]
        result (try
                 (p.eql/process env query)
                 (catch Exception e
                   {:error (.getMessage e)}))]
    (println (str "--- " label " ---"))
    (println "  Resolvers called:" @call-log)
    (println "  Result:" (pr-str result))
    (let [display (get-in result [:params/order :order/email-display])]
      (println "  :order/email-display =" (pr-str display))
      (println "  Expected: \"alice@example.com\"")
      (println "  Bug present?" (or (nil? display) (= "<missing>" display))))
    (println)))

(defn -main []
  (println "=== Pathom3: Pass-through Resolver Input Shape Bug ===\n")
  (println "Scenario:")
  (println "  1. order-lookup provides params/order-unsafe with {:order/id 1}")
  (println "  2. customer-loader can resolve {:order/customer [:customer/id :customer/email]}")
  (println "     given :order/id")
  (println "  3. authorize takes params/order-unsafe → params/order (pass-through)")
  (println "  4. email-display needs :customer/email from :order/customer")
  (println "")
  (println "The authorize resolver's INPUT declaration determines what Pathom")
  (println "resolves for the nested :order/customer BEFORE passing it through.\n")

  (run-test (str "BUGGY: authorize INPUT needs only {:order/customer [:customer/id]}\n"
                 "         → Pathom doesn't resolve :customer/email before pass-through")
            authorize-buggy)

  (run-test (str "FIXED: authorize INPUT needs {:order/customer [:customer/id (? :customer/email)]}\n"
                 "         → Pathom resolves customer-loader for :customer/email too")
            authorize-fixed)

  (println "=== Explanation ===")
  (println "The pass-through resolver (authorize) transforms params/order-unsafe into")
  (println "params/order. Its INPUT shape declaration tells Pathom what data to provide:")
  (println "")
  (println "  BUGGY input:  {(? :order/customer) [:customer/id]}")
  (println "  → Pathom calls customer-loader but only passes {:customer/id 1} to authorize")
  (println "  → authorize passes this through as params/order")
  (println "  → email-display gets {} for :order/customer (email was filtered out)")
  (println "")
  (println "  FIXED input:  {(? :order/customer) [:customer/id (? :customer/email)]}")
  (println "  → Pathom calls customer-loader and passes BOTH fields to authorize")
  (println "  → authorize passes the complete data through as params/order")
  (println "  → email-display gets {:customer/email \"alice@example.com\"}")
  (println "")
  (println "Key insight: Pathom's input declarations act as a shape filter. Even though")
  (println "customer-loader provides :customer/email, if the consuming resolver's input")
  (println "doesn't declare that field, Pathom filters it out before passing data in."))



