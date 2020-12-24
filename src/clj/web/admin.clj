(ns web.admin
  (:require [web.db :refer [db object-id]]
            [web.lobby :refer [all-games]]
            [game.main :as main]
            [tasks.nrdb :refer [fetch-data]]
            [web.utils :refer [response]]
            [monger.collection :as mc]
            [monger.result :refer [acknowledged?]]
            [monger.operators :refer :all]
            [web.config :refer [frontend-version]])
  (:import org.bson.types.ObjectId))

(defn wrap-version [handler]
  (fn [request]
    (handler (assoc request :version @frontend-version))))

(defn announcement-handler
  [{{:keys [message]} :params :as req}]
  (doseq [{state :state} (vals @all-games)]
    (when state
      (main/handle-announcement state message)))
  (response 200 {:message "ok"}))

(defn news-create-handler [{body :body}]
  (let [msg (:item body)]
    (if-not (empty? msg)
      (do
        (mc/insert db "news" {:_id (ObjectId.) :item msg :date (java.util.Date.)})
        (response 200 {:message "ok"}))
      (response 409 {:message "Missing news item"}))))

(defn news-delete-handler [{{id :id} :params}]
  (try
    (if id
      (if (acknowledged? (mc/remove db "news" {:_id (object-id id)}))
        (response 200 {:message "Deleted"})
        (response 403 {:message "Forbidden"}))
      (response 401 {:message "Missing new items id"}))
    (catch Exception ex
      (response 409 {:message "Unknown news item id"}))))

(defn version-handler
  [{{:keys [version]} :params :as req}]
  (reset! frontend-version version)
  (mc/update db "config" {} {$set {:version version}})
  (response 200 {:message "ok" :version version}))

(defn fetch-handler
  "Provide an admin endpoint for fetching card data. Options to fetch can be supplied as parameters to the fetch endpoint."
  [{params :params :as req}]
  (try
    (fetch-data params)
    (response 200 {:message "ok"})
    (catch Exception e (do
                         (println "fetch-handler failed:" (.getMessage e))
                         (.printStackTrace e)
                         (response 500 {:message (str "Import data failed: " (.getMessage e))})))))
