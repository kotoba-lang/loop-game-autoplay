(ns loop-game-autoplay.qualify
  "Qualify a trained champion on a real booted iOS Simulator, and record it.

  ```
  nbb --classpath src:../shinka/src:../hinshitsu/src -m loop-game-autoplay.qualify \\
      --champion target/run-champion.edn --seed 1
  ```

  Training happens in headless Chromium (see `loop-game-autoplay.train`).
  This is the other half: the *same* page, the *same* driver, the *same*
  genome, rendered by WebKit on a real device envelope, playing in real time,
  with the frames captured.

  ## Why `simctl openurl` and not a built app

  `kotoba-lang/shell` can scaffold and build a WKWebView host, install it and
  launch it, and that is the right path when the artefact under test is an
  app. Here the artefact under test is a **web game** and a policy; a wrapper
  app would add an Xcode project, a bundle ID and a signing story to the
  evidence chain without changing what is rendered. `openurl` reaches the same
  WebKit through Safari.

  What that costs, recorded honestly in the receipt: the video contains
  Safari's chrome, and there is no eval channel — which is why the driver
  reports its own outcome back to the server that served it.

  ## What the receipt claims

  `hinshitsu.core/evidence` maps, aggregated by `hinshitsu.core/gate`. The
  blankness check is ImageMagick's mean brightness on a real screenshot, not
  a file-size proxy: a black screen is the failure mode that most looks like
  success."
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [hinshitsu.core :as h]
            [loop-game-autoplay.policy :as policy]
            [loop-game-autoplay.server :as server]))

(defn- arg [args k default]
  (if-let [i (first (keep-indexed #(when (= %2 (str "--" k)) %1) args))]
    (nth args (inc i) default)
    default))

(defn- sh
  "Run a command and return `{:code :out :err}`. Synchronous on purpose --
  every call here is a simctl step whose ordering is the point."
  [cmd argv]
  (let [r (cp/spawnSync cmd (clj->js argv) #js {:encoding "utf8"})]
    {:code (.-status r)
     :out (str/trim (or (.-stdout r) ""))
     :err (str/trim (or (.-stderr r) ""))}))

(defn- booted-udid []
  (let [{:keys [out]} (sh "xcrun" ["simctl" "list" "devices" "booted" "-j"])
        j (try (js->clj (js/JSON.parse out) :keywordize-keys true) (catch :default _ nil))
        devs (mapcat val (:devices j))]
    (some :udid devs)))

(defn- sleep [ms] (js/Promise. (fn [r] (js/setTimeout r ms))))

(defn- mean-brightness
  "0.0 (black) .. 1.0 (white) via ImageMagick. `nil` if it could not be read."
  [png]
  (let [{:keys [code out]} (sh "magick" ["identify" "-format" "%[fx:mean]" png])]
    (when (zero? code) (js/parseFloat out))))

(defn- wait-for-result
  "Poll the server's result box. The page pushes; we do not guess a duration."
  [srv timeout-ms]
  (let [deadline (+ (js/Date.now) timeout-ms)
        step (fn step []
               (cond
                 (seq @(:results srv)) (js/Promise.resolve (first @(:results srv)))
                 (> (js/Date.now) deadline) (js/Promise.resolve nil)
                 :else (.then (sleep 500) step)))]
    (step)))

(defn -main [& args]
  (let [champion-file (arg args "champion" "target/run-champion.edn")
        out-dir (arg args "out-dir" "target/qualify")
        seed (js/parseInt (arg args "seed" "1") 10)
        timeout-ms (js/parseInt (arg args "timeout-ms" "180000") 10)
        udid (or (arg args "udid" nil) (booted-udid))]
    (when-not (fs/existsSync champion-file)
      (println "champion not found:" champion-file) (js/process.exit 2))
    (when-not udid
      (println "no booted simulator. `xcrun simctl boot <udid>` first.") (js/process.exit 2))
    (let [champ (edn/read-string (fs/readFileSync champion-file "utf8"))
          game (:game champ)
          _ (when-not (fs/existsSync game)
              (println "game file from champion not found:" game) (js/process.exit 2))
          _ (when-not (fs/existsSync out-dir) (fs/mkdirSync out-dir #js {:recursive true}))
          video (path/join out-dir "run.mp4")
          shot (path/join out-dir "run.png")]
      (println (str "qualify: udid " udid))
      (println (str "champion fitness " (:fitness champ) " from generation " (:found-in-generation champ)))
      (-> (server/start! {:game-file game})
          (.then
           (fn [srv]
             (let [url (str "http://127.0.0.1:" (:port srv) "/?mode=play&seed=" seed
                            "&genome=" (js/encodeURIComponent (js/JSON.stringify (clj->js (:genome champ)))))
                   recorder (cp/spawn "xcrun" (clj->js ["simctl" "io" udid "recordVideo" "--codec" "h264" "-f" video])
                                      #js {:stdio "ignore"})]
               (println (str "serve:  http://127.0.0.1:" (:port srv) "/"))
               (println "record: started")
               (-> (sleep 1500)
                   (.then (fn [_]
                            (let [r (sh "xcrun" ["simctl" "openurl" udid url])]
                              (println (str "openurl: exit " (:code r) (when (seq (:err r)) (str " " (:err r)))))
                              r)))
                   (.then (fn [openurl] (.then (sleep 12000) (fn [_] openurl))))
                   ;; mid-run screenshot: the frame that proves the game was
                   ;; actually rendering, not that the page merely loaded
                   (.then (fn [openurl]
                            (let [s (sh "xcrun" ["simctl" "io" udid "screenshot" shot])]
                              {:openurl openurl :shot s})))
                   (.then (fn [ctx] (.then (wait-for-result srv (- timeout-ms 13500))
                                           (fn [res] (assoc ctx :result res)))))
                   (.then
                    (fn [ctx]
                      (.kill recorder "SIGINT")
                      (-> (sleep 2500)
                          (.then (fn [_] ctx)))))
                   (.then
                    (fn [{:keys [openurl shot result]}]
                      (server/stop! srv)
                      (let [vsize (if (fs/existsSync video) (.-size (fs/statSync video)) 0)
                            bright (when (fs/existsSync shot) (mean-brightness shot))
                            evidences
                            [(h/evidence (if (zero? (:code openurl)) :passed :failed)
                                         ["openurl"]
                                         (str "simctl openurl exited " (:code openurl))
                                         {:command ["xcrun" "simctl" "openurl" udid "<url>"]})
                             (h/evidence (if (and (zero? (:code shot)) (fs/existsSync shot)) :passed :failed)
                                         ["screenshot"]
                                         (str "screenshot " (if (fs/existsSync shot) "written" "missing"))
                                         {:data {:path shot}})
                             (h/evidence (cond (nil? bright) :skipped
                                               (> bright 0.02) :passed
                                               :else :failed)
                                         ["not-blank"]
                                         (str "mean brightness " bright
                                              " (a black screen is the failure that looks like success)")
                                         {:data {:mean-brightness bright :threshold 0.02}})
                             (h/evidence (if (> vsize 100000) :passed :failed)
                                         ["recorded"]
                                         (str "video " vsize " bytes")
                                         {:data {:path video :bytes vsize}})
                             (h/evidence (cond (nil? result) :failed
                                               (> (:survivedMs result) 5000) :passed
                                               :else :failed)
                                         ["played"]
                                         (if result
                                           (str "survived " (.toFixed (/ (:survivedMs result) 1000) 1)
                                                "s, level " (:level result)
                                                ", " (if (:won result) "won" "died"))
                                           "the page never reported a result")
                                         {:data result})]
                            g (h/gate evidences {:required-checks #{"openurl" "played" "recorded" "not-blank"}})
                            receipt {:schema "loop-game-autoplay.qualify/v1"
                                     :udid udid
                                     :device-runtime (:out (sh "xcrun" ["simctl" "list" "devices" "booted"]))
                                     :game game
                                     :driver "autoplay-driver/v1"
                                     :champion {:file champion-file
                                                :training-fitness (:fitness champ)
                                                :found-in-generation (:found-in-generation champ)
                                                :obs-dim (:obs-dim champ)
                                                :act-dim (:act-dim champ)}
                                     :play-seed seed
                                     :on-device result
                                     :artifacts {:video video :video-bytes vsize
                                                 :screenshot shot :mean-brightness bright}
                                     :evidence evidences
                                     :gate g}]
                        (fs/writeFileSync (path/join out-dir "receipt.edn") (pr-str receipt))
                        (println "")
                        (doseq [e evidences]
                          (println (str "  " (name (:hinshitsu/status e))
                                        "  " (str/join "," (:hinshitsu/checks e))
                                        "  " (:hinshitsu/detail e))))
                        (println (str "\ngate: " (:hinshitsu/status g)))
                        (println (str "receipt: " (path/join out-dir "receipt.edn")))
                        (js/process.exit (if (h/passed? g) 0 1)))))))))
          (.catch (fn [e] (println "ERROR" (str e)) (js/process.exit 1)))))))
