(ns web.game
  (:require
   [cheshire.core :as json]
   [cljc.java-time.instant :as inst]
   [clojure.stacktrace :as stacktrace]
   [cond-plus.core :refer [cond+]]
   [game.core :as core]
   [game.core.diffs :as diffs]
   [game.main :as main]
   [jinteki.utils :refer [side-from-str]]
   [medley.core :refer [find-first]]
   [web.app-state :as app-state]
   [web.lobby :as lobby]
   [web.stats :as stats]
   [web.ws :as ws]))

(defn game-diff-json
  "Converts the appropriate diff to json"
  [gameid side {:keys [runner-diff corp-diff spect-diff]}]
  (json/generate-string {:gameid gameid
                         :diff (cond
                                 (= side "Corp")
                                 corp-diff
                                 (= side "Runner")
                                 runner-diff
                                 :else
                                 spect-diff)}))

(defn send-state-diffs
  "Sends diffs generated by public-diffs to all connected clients."
  [{:keys [gameid players spectators]} diffs]
  (doseq [{:keys [uid side]} (concat players spectators)
          :when (some? uid)]
    (ws/chsk-send! uid [:game/diff (game-diff-json gameid side diffs)])))

(defn update-and-send-diffs!
  "Updates the old-states atom with the new game state, then sends a :game/diff
  message to game clients."
  [f {state :state :as lobby} & args]
  (when (and state @state)
    (let [old-state @state
          _ (apply f state args)
          diffs (diffs/public-diffs old-state state)]
      (swap! state update :history conj (:hist-diff diffs))
      (send-state-diffs lobby diffs))))

(defn select-state [side {:keys [runner-state corp-state spect-state]}]
  (json/generate-string
    (case side
      "Corp" corp-state
      "Runner" runner-state
      spect-state)))

(defn send-state-to-participants
  "Sends full states generated by public-states to all connected clients in lobby."
  [event {:keys [players spectators]} diffs]
  (doseq [{:keys [uid side]} (concat players spectators)
          :when (some? uid)]
    (ws/chsk-send! uid [event (select-state side diffs)])))

(defn send-state-to-uid!
  "Sends full states generated by public-states to single client in lobby."
  [uid event {:keys [players spectators]} diffs]
  (when-let [player (find-first #(= (:uid %) uid) (concat players spectators))]
    (ws/chsk-send! uid [event (select-state (:side player) diffs)])))

(defn- is-starter-deck?
  [player]
  (let [id (get-in player [:deck :identity :title])
        card-cnt (reduce + (map :qty (get-in player [:deck :cards])))]
    (or (and (= id "The Syndicate: Profit over Principle")
             (= card-cnt 34))
        (and (= id "The Catalyst: Convention Breaker")
             (= card-cnt 30)))))

(defn- check-for-starter-decks
  "Starter Decks can require 6 or 7 agenda points"
  [game]
  (if (and (= (:format game) "system-gateway")
           (every? is-starter-deck? (:players game)))
    (do
      (swap! (:state game) assoc-in [:runner :agenda-point-req] 6)
      (swap! (:state game) assoc-in [:corp :agenda-point-req] 6)
      game)
    game))

(defn strip-deck [player]
  (-> player
      (update :deck select-keys [:_id :identity :name :hash])
      (update-in [:deck :_id] str)
      (update-in [:deck :identity] select-keys [:title :faction])))

(defn handle-start-game [lobbies gameid players now]
  (if-let [lobby (get lobbies gameid)]
    (as-> lobby g
      (merge g {:started true
                :original-players players
                :ending-players players
                :start-date now
                :last-update now
                :state (core/init-game g)})
      (check-for-starter-decks g)
      (update g :players #(mapv strip-deck %))
      (assoc lobbies gameid g))
    lobbies))

(defmethod ws/-msg-handler :game/start
  [{{db :system/db} :ring-req
    uid :uid
    {gameid :gameid} :?data}]
  (let [{:keys [players started] :as lobby} (app-state/get-lobby gameid)]
    (when (and lobby (lobby/first-player? uid lobby) (not started))
      (let [now (inst/now)
            new-app-state
            (swap! app-state/app-state
                   update :lobbies handle-start-game gameid players now)
            lobby? (get-in new-app-state [:lobbies gameid])]
        (when lobby?
          (stats/game-started db lobby?)
          (lobby/send-lobby-state lobby?)
          (lobby/broadcast-lobby-list)
          (send-state-to-participants :game/start lobby? (diffs/public-states (:state lobby?))))))))

(defmethod ws/-msg-handler :game/leave
  [{{db :system/db user :user} :ring-req
    uid :uid
    {gameid :gameid} :?data
    ?reply-fn :?reply-fn}]
  (let [{:keys [started state] :as lobby} (app-state/get-lobby gameid)]
    (when (and lobby (lobby/in-lobby? uid lobby) started state)
      ; The game will not exist if this is the last player to leave.
      (when-let [lobby? (lobby/leave-lobby! db user uid nil lobby)]
        (update-and-send-diffs!
          main/handle-notification lobby? (str (:username user) " has left the game.")))
      (lobby/send-lobby-list uid)
      (lobby/broadcast-lobby-list)
      (when ?reply-fn (?reply-fn true)))))

(defn uid-in-lobby-as-original-player? [uid]
  (find-first
    (fn [lobby]
      (some #(= uid (:uid %)) (:original-players lobby)))
    (vals (:lobbies @app-state/app-state))))

(defmethod ws/-msg-handler :game/rejoin
  [{{user :user} :ring-req
    uid :uid
    ?data :?data}]
  (let [{:keys [original-players started players] :as lobby} (uid-in-lobby-as-original-player? uid)
        original-player (find-first #(= uid (:uid %)) original-players)]
    (when (and started
               original-player
               (< (count (remove #(= uid (:uid %)) players)) 2))
      (let [?data (assoc ?data :request-side "Any Side")
            lobby? (lobby/join-lobby! user uid ?data nil lobby)]
        (when lobby?
          (send-state-to-uid! uid :game/start lobby? (diffs/public-states (:state lobby?)))
          (update-and-send-diffs! main/handle-rejoin lobby? user))))))

(defmethod ws/-msg-handler :game/concede
  [{uid :uid
    {gameid :gameid} :?data}]
  (let [lobby (app-state/get-lobby gameid)
        player (lobby/player? uid lobby)]
    (when (and lobby player)
      (let [side (side-from-str (:side player))]
        (update-and-send-diffs! main/handle-concede lobby side)))))

(defmethod ws/-msg-handler :game/action
  [{uid :uid
    {:keys [gameid command args]} :?data}]
  (try
    (let [{:keys [state] :as lobby} (app-state/get-lobby gameid)
          player (lobby/player? uid lobby)
          spectator (lobby/spectator? uid lobby)]
      (cond
        (and state player)
        (let [old-state @state
              side (side-from-str (:side player))]
          (try
            (swap! app-state/app-state
                   update :lobbies lobby/handle-set-last-update gameid uid)
            (update-and-send-diffs! main/handle-action lobby side command args)
            (catch Exception e
              (reset! state old-state)
              (throw e))))
        (and (not spectator) (not= command "toast"))
        (throw (ex-info "handle-game-action unknown state or side"
                        {:gameid gameid
                         :uid uid
                         :players (map #(select-keys % [:uid :side]) (:players lobby))
                         :spectators (map #(select-keys % [:uid]) (:spectators lobby))
                         :command command
                         :args args}))))
    (catch Exception e
      (ws/chsk-send! uid [:game/error])
      (println (str "Caught exception"
                    "\nException Data: " (or (ex-data e) (.getMessage e))
                    "\nStacktrace: " (with-out-str (stacktrace/print-stack-trace e 100)))))))

(defmethod ws/-msg-handler :game/resync
  [{uid :uid
    {gameid :gameid} :?data}]
  (let [lobby (app-state/get-lobby gameid)]
    (when (and lobby (lobby/in-lobby? uid lobby))
      (if-let [state (:state lobby)]
        (send-state-to-uid! uid :game/resync lobby (diffs/public-states state))
        (println (str "resync request unknown state"
                      "\nGameID:" gameid
                      "\nGameID by ClientID:" gameid
                      "\nClientID:" uid
                      "\nPlayers:" (map #(select-keys % [:uid :side]) (:players lobby))
                      "\nSpectators" (map #(select-keys % [:uid]) (:spectators lobby))))))))

(defmethod ws/-msg-handler :game/watch
  [{{user :user} :ring-req
    uid :uid
    {:keys [gameid password]} :?data
    ?reply-fn :?reply-fn}]
  (let [lobby (app-state/get-lobby gameid)]
    (when (and lobby (lobby/allowed-in-lobby user lobby))
      (let [correct-password? (lobby/check-password lobby user password)
            watch-message (core/make-system-message (str (:username user) " joined the game as a spectator."))
            new-app-state (swap! app-state/app-state
                                 update :lobbies
                                 #(-> %
                                      (lobby/handle-watch-lobby gameid uid user correct-password? watch-message)
                                      (lobby/handle-set-last-update gameid uid)))
            lobby? (get-in new-app-state [:lobbies gameid])]
        (cond
          (and lobby? (lobby/spectator? uid lobby?) (lobby/allowed-in-lobby user lobby?))
          (let [message (str (:username user) " joined the game as a spectator.")]
            (lobby/send-lobby-state lobby?)
            (lobby/send-lobby-ting lobby?)
            (lobby/broadcast-lobby-list)
            (main/handle-notification (:state lobby?) message)
            (send-state-to-uid! uid :game/start lobby? (diffs/public-states (:state lobby?)))
            (when ?reply-fn (?reply-fn 200)))
          (false? correct-password?)
          (when ?reply-fn (?reply-fn 403))
          :else
          (when ?reply-fn (?reply-fn 404)))))))

(defmethod ws/-msg-handler :game/mute-spectators
  [{{user :user} :ring-req
    uid :uid
    {gameid :gameid} :?data}]
  (let [new-app-state (swap! app-state/app-state update :lobbies #(-> %
                                                                      (lobby/handle-toggle-spectator-mute gameid uid)
                                                                      (lobby/handle-set-last-update gameid uid)))
        {:keys [state mute-spectators] :as lobby?} (get-in new-app-state [:lobbies gameid])
        message (if mute-spectators "unmuted" "muted")]
    (when (and lobby? state (lobby/player? uid lobby?))
      (update-and-send-diffs! main/handle-notification lobby? (str (:username user) " " message " spectators."))
      ;; needed to update the status bar
      (lobby/send-lobby-state lobby?))))

(defmethod ws/-msg-handler :game/say
  [{{user :user} :ring-req
    uid :uid
    {:keys [gameid msg]} :?data}]
  (let [new-app-state (swap! app-state/app-state update :lobbies lobby/handle-set-last-update gameid uid)
        {:keys [state mute-spectators] :as lobby?} (get-in new-app-state [:lobbies gameid])
        side (cond+
               [(lobby/player? uid lobby?) :> #(side-from-str (:side %))]
               [(and (not mute-spectators) (lobby/spectator? uid lobby?)) :spectator])]
    (when (and lobby? state side)
      (update-and-send-diffs! main/handle-say lobby? side user msg))))

(defmethod ws/-msg-handler :game/typing
  [{uid :uid
    {:keys [gameid typing]} :?data}]
  (let [{:keys [state players] :as lobby} (app-state/get-lobby gameid)]
    (when (and state (lobby/player? uid lobby))
      (doseq [{:keys [uid]} (remove #(= uid (:uid %)) players)]
        (ws/chsk-send! uid [:game/typing typing])))))

(defmethod ws/-msg-handler :chsk/uidport-close
  [{{db :system/db
     user :user} :ring-req
    uid :uid
    ?reply-fn :?reply-fn}]
  (let [{:keys [started state] :as lobby} (app-state/uid->lobby uid)]
    (when (and started state)
      ; The game will not exist if this is the last player to leave.
      (when-let [lobby? (lobby/leave-lobby! db user uid nil lobby)]
        (update-and-send-diffs!
          main/handle-notification lobby? (str (:username user) " has left the game.")))))
  (lobby/send-lobby-list uid)
  (lobby/broadcast-lobby-list)
  (when ?reply-fn (?reply-fn true)))
