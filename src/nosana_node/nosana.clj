(ns nosana-node.nosana
  (:require [integrant.core :as ig]
            [nos.ops.docker :as docker]
            [nos.core :as flow]
            [clojure.edn :as edn]
            [clj-http.client :as http]
            [clojure.core.async :as async :refer
             [<!! <! >!! go go-loop >! timeout take! chan put!]]
            [taoensso.timbre :as logg :refer [log]]
            [nos.vault :as vault]
            [konserve.core :as kv]
            [clojure.string :as string]
            [cheshire.core :as json]
            [nosana-node.util
             :refer [ipfs-hash->bytes bytes->ipfs-hash]
             :as util]
            [nosana-node.solana :as sol])
  (:import java.util.Base64
           java.security.MessageDigest
           [java.time Instant Duration]
           [org.p2p.solanaj.core Transaction TransactionInstruction PublicKey
            Account Message AccountMeta]
           [org.p2p.solanaj.utils ByteUtils]
           [org.p2p.solanaj.rpc RpcClient Cluster]
           [org.bitcoinj.core Utils Base58]))

;; (def ipfs-base-url "https://cloudflare-ipfs.com/ipfs/")
(def pinata-api-url "https://api.pinata.cloud")

(defn ipfs-upload
  "Converts a map to a JSON string and pins it using Pinata.
  Returns the CID string of the IPFS object."
  [obj {:keys [pinata-jwt]}]
  (when (not pinata-jwt)
    (throw (ex-info "Pinata JWT not set" {})))
  (log :trace "Uploading object to ipfs")
  (->
   (http/post (str pinata-api-url "/pinning/pinJSONToIPFS")
              {:headers {:Authorization (str "Bearer " pinata-jwt)}
               :content-type :json
               :body (json/encode obj)})
   :body
   json/decode
   (get "IpfsHash")))

(def nos-accounts
  {:mainnet {:nos-token   (PublicKey. "nosXBVoaCTtYdLvKY6Csb4AC8JCdQKKAaWYtx2ZMoo7")
             :stake       (PublicKey. "nosScmHY2uR24Zh751PmGj9ww9QRNHewh9H59AfrTJE")
             :collection  (PublicKey. "nftNgYSG5pbwL7kHeJ5NeDrX8c4KrG1CzWhEXT8RMJ3")
             :job         (PublicKey. "nosJhNRqr2bc9g1nfGDcXXTXvYUmxD4cVwy2pMWhrYM")
             :reward      (PublicKey. "nosRB8DUV67oLNrL45bo2pFLrmsWPiewe2Lk2DRNYCp")
             :pool        (PublicKey. "nosPdZrfDzND1LAR28FLMDEATUPK53K8xbRBXAirevD")
             :reward-pool (PublicKey. "mineHEHiHxWS8pVkNc5kFkrvv5a9xMVgRY9wfXtkMsS")
             :dummy       (PublicKey. "dumxV9afosyVJ5LNGUmeo4JpuajWXRJ9SH8Mc8B3cGn")}
   :devnet  {:nos-token   (PublicKey. "devr1BGQndEW5k5zfvG5FsLyZv1Ap73vNgAHcQ9sUVP")
             :stake       (PublicKey. "nosScmHY2uR24Zh751PmGj9ww9QRNHewh9H59AfrTJE")
             :collection  (PublicKey. "CBLH5YsCPhaQ79zDyzqxEMNMVrE5N7J6h4hrtYNahPLU")
             :job         (PublicKey. "nosJhNRqr2bc9g1nfGDcXXTXvYUmxD4cVwy2pMWhrYM")
             :reward      (PublicKey. "nosRB8DUV67oLNrL45bo2pFLrmsWPiewe2Lk2DRNYCp")
             :pool        (PublicKey. "nosPdZrfDzND1LAR28FLMDEATUPK53K8xbRBXAirevD")
             :reward-pool (PublicKey. "miF9saGY5WS747oia48WR3CMFZMAGG8xt6ajB7rdV3e")
             :dummy       (PublicKey. "dumxV9afosyVJ5LNGUmeo4JpuajWXRJ9SH8Mc8B3cGn")}})

(def download-ipfs
  "Download a file from IPFS by its hash."
  (memoize
   (fn [hash {:keys [ipfs-url]}]
     (log :trace "Downloading IPFS file " hash)
     (-> (str ipfs-url hash) http/get :body (json/decode true)))))

(defn download-job-ipfs
  [bytes conf]
  (-> bytes
      byte-array
      bytes->ipfs-hash
      (download-ipfs conf)))

(defn sha256 [string]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes string "UTF-8"))]
    (apply str (map (partial format "%02x") digest))))

(defn hash-repo-url
  "Creates a short hash for a repo url"
  [name]
  (->> name sha256 (take 20) (reduce str)))

(defn get-signer-key
  "Retreive the signer key from the vault"
  [vault]
  (Account. (byte-array (edn/read-string (vault/get-secret vault :solana-private-key)))))

(defn solana-tx-failed?
  "Return true if a solana transaction failed.
  This is when it contains an error object in its metadata"
  [tx]
  (-> tx :meta :err nil? not))

(defn format-nos [v]
  (BigDecimal. (BigInteger. v)  6))

(defn get-health
  "Queuery health statistics for the node."
  [{:keys [address network nos-ata accounts]}]
  {:sol (sol/format-sol (str (sol/get-balance address network)))
   :nos (format-nos (or (sol/get-token-balance nos-ata network) "0"))
   :nft (if (= (get accounts "nft") nos-ata)
          0
          (Integer/parseInt
           (or (sol/get-token-balance (get accounts "nft") network)
               "0")))})

(def min-sol-balance
  "Minimum Solana balance to be healthy" (sol/format-sol "10000000"))

(defn healthy
  "Check if the current node is healthy."
  [conf]
  (let [{:keys [sol nos nft] :as health} (get-health conf)]
    (let [has-docker? (try (docker/get-info {:uri (:podman-uri conf)})
                           true
                           (catch Exception e false))

          msgs (cond-> []
                 (nil? (:signer conf))
                 (conj "No signer keypair provided, please run `solana-keygen new`")

                 (< sol min-sol-balance)
                 (conj (str "SOL balance is too low to operate."))

                 (and (< nft 1.0) (not (:open-market? conf)))
                 (conj (str "Burner Phone NFT is missing"))

                 (nil? (:pinata-jwt conf))
                 (conj "Pinata JWT not found, node will not be able to submit any jobs.")

                 (not has-docker?)
                 (conj (str "Could not connect to Podman at " (:podman-uri conf))))]
      (if (empty? msgs)
        [:success health]
        [:error health msgs]))))

(defn flow-finished? [flow]
  (flow/finished? flow))

(defn flow-expired? [flow]
  (and (contains? flow :expires)
       (> (flow/current-time) (:expires flow))))

(defn flow-git-failed?
  "Check if one of the git operations in a flow falied

  They are pre-requisite for the docker commands and we catch them separately"
  [flow]
  (or (= ::flow/error (get-in flow [:results :clone 0]))
      (= ::flow/error (get-in flow [:results :checkout 0]))))

(def ascii-logo "  _ __   ___  ___  __ _ _ __   __ _
 | '_ \\ / _ \\/ __|/ _` | '_ \\ / _` |
 | | | | (_) \\__ \\ (_| | | | | (_| |
 |_| |_|\\___/|___/\\__,_|_| |_|\\__,_|")

;;(nos/print-head "v0.3.19" "4HoZogbrDGwK6UsD1eMgkFKTNDyaqcfb2eodLLtS8NTx" "0.084275" "13,260.00")
(defn print-head [version address network market balance stake nft owned]
  (logg/infof "
%s

Running Nosana Node %s

  Validator  \u001B[34m%s\u001B[0m
  Network    Solana \u001B[34m%s\u001B[0m
  Market     \u001B[34m%s\u001B[0m
  Balance    \u001B[34m%s\u001B[0m SOL
  Stake      \u001B[34m%s\u001B[0m NOS
  Slashed    \u001B[34m0.00\u001B[0m NOS
  NFT        \u001B[34m%s\u001B[0m
  Owned      \u001B[34m%s\u001B[0m NFT
"
              ascii-logo
              version
              address
              network
              market
              balance
              stake
              nft
              owned))

(defn make-config
  "Build the node's config to interact with the Nosana Network."
  [{:keys [:nos/vault]}]
  (let [network      (:solana-network vault)
        signer       (try (get-signer-key vault)
                          (catch Exception e
                            nil))
        signer-pub   (if signer
                       (.getPublicKey signer)
                       (:system sol/addresses))
        programs     (network nos-accounts)
        market-pub   (if (:nosana-market vault)
                       (PublicKey. (:nosana-market vault))
                       (get-in nos-accounts [network :market]))
        market-vault (sol/pda
                      [(.toByteArray market-pub)
                       (.toByteArray (:nos-token programs))]
                      (:job programs))
        stake        (when signer
                       (sol/pda
                        [(.getBytes "stake")
                         (.toByteArray (:nos-token programs))
                         (.toByteArray (.getPublicKey signer))]
                        (:stake programs)))
        nos-ata      (sol/get-ata signer-pub (:nos-token programs))

        market (sol/get-idl-account (:job programs) "MarketAccount" market-pub network)

        open-market? (= (.toString (:nodeAccessKey market))
                        (.toString (:system sol/addresses)))

        nft (cond
              (:nft vault) (PublicKey. (:nft vault))
              open-market? (:system sol/addresses)
              :else
              (sol/get-nft-from-collection
               signer-pub
               (:nodeAccessKey market)
               network))

        ;; when the market does not have an NFT, we pass the nos-ata
        ;; as the nft-ata. this is because the program requires the
        ;; account to be a valid TokenAccount (without further
        ;; constraints).
        nft-ata (cond
                  open-market? nos-ata
                  :else (sol/get-ata signer-pub nft))

        metadata (cond
                   open-market? (:system sol/addresses)
                   :else (sol/get-metadata-pda nft))]
    {:network           network
     :signer            signer
     :secrets-endpoint  "https://secrets.k8s.dev.nos.ci"
     :pinata-jwt        (:pinata-jwt vault)
     :ipfs-url          (:ipfs-url vault)
     :market            market-pub
     :nos-default-args  {:container/run
                         {:conn         {:uri [:nos/vault :podman-conn-uri]}
                          :inline-logs? true}}
     :open-market?      open-market?
     :market-collection (:nodeAccessKey market)
     :job-timeout       (:jobTimeout market)
     :address           signer-pub
     :programs          programs
     :nft               nft
     :podman-uri        (:podman-conn-uri vault)
     :nos-ata           nos-ata
     :stake-vault       (sol/pda [(.getBytes "vault")
                                  (.toByteArray (:nos-token programs))
                                  (.toByteArray signer-pub)]
                                 (:stake programs))
     :accounts          {"tokenProgram"      (:token sol/addresses)
                         "systemProgram"     (:system sol/addresses)
                         "rent"              (:rent sol/addresses)
                         "accessKey"         (:nodeAccessKey market)
                         "authority"         signer-pub
                         "user"              nos-ata
                         "payer"             signer-pub
                         "market"            market-pub
                         "mint"              (:nos-token programs)
                         "vault"             market-vault
                         "stake"             stake
                         "nft"               nft-ata
                         "metadata"          metadata
                         "rewardsProgram"    (:reward programs)
                         "rewardsVault"      (sol/pda [(.toByteArray (:nos-token programs))]
                                                      (:reward programs))
                         "rewardsReflection" (sol/pda [(.getBytes "reflection")]
                                                      (:reward programs))}}))

(defn build-idl-tx
  "Wrapper around `solana/build-idl-tx` using nosana config"
  [program ins args {:keys [network accounts]} extra-accounts]
  (sol/build-idl-tx
   (-> nos-accounts network program)
   ins
   args
   (merge accounts extra-accounts)
   network))

(defn list-job
  "List a job.
  If `job` is a map it will be uploaded to IPFS, if it is a string it
  will be used as the IPFS CID."
  [conf job]
  (let [hash (if (map? job)
               (ipfs-upload job conf)
               job)
        job  (sol/account)
        run  (sol/account)
        tx   (build-idl-tx :job "list" [(ipfs-hash->bytes hash)]
                           conf {"job" (.getPublicKey job)
                                 "run" (.getPublicKey run)})]
    (log :info "Listing job with hash " (-> job .getPublicKey .toString) hash)
    (sol/send-tx tx [(:signer conf) job run] (:network conf))))

(defn list-cicd-job
  "List a job in the market from a pipeline yaml file."
  [conf url commit pipeline-file]
  (let [job {:type     "Github"
             :url      url
             :commit   commit
             :state {:nosana/secrets ["npm-deploy-key"]}
             :pipeline (slurp pipeline-file)}]
    (list-job conf job)))

(defn enter-market
  "Enter market, assuming there are no jobs in the queue."
  [conf]
  (let [run (Account.)
        tx  (build-idl-tx :job "work" []
                          conf {"run" (.getPublicKey run)})]
    (try
      (sol/send-tx tx [(:signer conf) run] (:network conf))
      (catch Exception e
        (log :error "Failed entering market" e)
        nil))))

(defn exit-market
  "Exit the market node queue."
  [conf]
  (let [run (Account.)
        tx  (build-idl-tx :job "stop" [] conf {})]
    (try
      (sol/send-tx tx [(:signer conf)] (:network conf))
      (catch Exception e
        (log :error "Failed exit market" e)
        nil))))

(defn get-job [{:keys [network programs]} addr]
  (sol/get-idl-account (:job programs) "JobAccount" addr network))

(defn get-run [{:keys [network programs]} addr]
  (sol/get-idl-account (:job programs) "RunAccount" addr network))

(defn finish-job
  "Post results for an owned job."
  [{:keys [network signer] :as conf} job-addr run-addr market-addr ipfs-hash]
  (let [run (get-run conf run-addr)]
    (-> (build-idl-tx :job "finish"
                      [(ipfs-hash->bytes ipfs-hash)]
                      conf
                      {"job"   job-addr
                       "run"   run-addr
                       "payer" (:payer run)
                       "market" market-addr})
        (sol/send-tx [signer] network))))

(defn quit-job
  "Quit a job."
  [{:keys [network signer] :as conf} run-addr]
  (let [run (get-run conf run-addr)]
    (-> (build-idl-tx :job "quit" [] conf
                      {"job"   (:job run)
                       "run"   run-addr
                       "payer" (:payer run)})
        (sol/send-tx [signer] network))))

(defn get-market [{:keys [network programs market]}]
  (sol/get-idl-account (:job programs) "MarketAccount" market network))

(defn find-my-runs
  "Find job accounts owned by this node"
  [{:keys [network programs address]}]
  (try
    (sol/get-idl-program-accounts
     network
     (:job programs)
     "RunAccount"
     {"node"  (.toString address)})
    (catch Exception e
      (log :error "RPC error fetching runs" e)
      {})))

(defn clear-market
  "Claim all the jobs that are queued in the configured market.
  Can be combined with `quit-my-runs`."
  [conf]
  (let [queue (:queue (get-market conf))
        job (first queue)]
    (logg/info "Market has " (count queue) " jobs")
    (doseq [i (range (count queue))]
      (logg/info "Entering market i = " i)
      (enter-market conf))))

(defn find-my-jobs
  "Find job accounts owned by this node"
  [{:keys [network programs address]}]
  (sol/get-idl-program-accounts
   network
   (:job programs)
   "JobAccount"
   {"node"  (.toString address)
    "state" "2"}))

(defn quit-my-runs
  "Quit all the jobs that are claimed by this node."
  [conf]
  (let [my-runs (find-my-runs conf)]
    (logg/info "Node has " (count my-runs) " claimed jobs")
    (doseq [[pub _] my-runs]
      (logg/info "Quiting run " pub)
      (quit-job conf (sol/public-key pub)))))

(defn create-market
  "Create a Nosana market.
  TODO: This function does not work yet because i64 support has to be
  added."
  [{:keys [network signer] :as conf}]
  (let [market-acc (sol/account)]
    (-> (build-idl-tx :job "open" ["360" "0" "360" 1 "0"] conf {:market (.getPublicKey market-acc)})
        (sol/send-tx [signer] network))))

(defn is-queued?
  "Returns `true` if the node is queued in the configured market.
  Returns `nil` if an error occured, for example when fetching the
  market."
  [conf]
  (try
    (let [market (get-market conf)]
      (not-empty (filter #(.equals %1 (:address conf)) (:queue market))))
    (catch Exception e
      (log :error "Failed checking if node is queued" e)
      nil)))

(defn- finish-flow-dispatch [flow conf]
  (or (get-in flow [:state :nosana/job-type])
      "Pipeline"))

(defmulti finish-flow
  "Process a finished flow by its [:state :nosana/job-type] value.
  Returns a document of the flow results."
  #'finish-flow-dispatch)

(defn finish-flow-2 [flow conf]
  (go
    (let [job-addr    (get-in flow [:state :input/job-addr])
          run-addr    (get-in flow [:state :input/run-addr])
          result-ipfs (finish-flow flow conf)
          job         (get-job conf job-addr)
          sig         (finish-job conf
                                  (PublicKey. job-addr)
                                  (PublicKey. run-addr)
                                  (:market job)
                                  result-ipfs)
          tx          (<! (sol/await-tx< sig (:network conf)))]
      (log :info "Job results posted " result-ipfs sig)
      nil)))

(defn process-flow!
  "Check the state of a flow and finalize its job if finished.
  Returns nil if successful, `flow-id` if not finished or if an
  exception occured."
  [flow-id conf {:nos/keys [store flow-chan vault] :as sys}]
  (go
    (try
      (let [flow     (<! (kv/get store flow-id))
            run-addr (get-in flow [:state :input/run-addr])]
        (cond
          (flow-finished? flow)
          (do
            (log :info "Flow finished, posting results")
            ;; TODO: consider moving garbage-collecting to an op in
            ;; the flow
            (docker/gc-volumes! flow {:uri (:podman-conn-uri vault)})
            (<! (finish-flow-2 flow conf)))
          (flow-expired? flow)
          (do
            (log :info "Flow has expired at " (:expired flow))
            (let [sig (quit-job conf (sol/public-key run-addr))]
              (<! (sol/await-tx< sig (:network conf)))
              nil))
          :else
          (let [_ (log :trace "Flow still running")]
            flow-id)))
      (catch Exception e
        (log :error "Failed processing flow " e)
        flow-id))))

(defn- create-flow-dispatch [job run-addr run conf]
  (prn "Checking Type Of Job " job)
  (or (get-in job [:state :nosana/job-type])
      "Pipeline"))

(defmulti create-flow
  "Create a Nostromo flow from a Nosana job.
  Dispatches on the [:state :nosana/job-type] value."
  #'create-flow-dispatch)

(defn start-flow-for-run!
  "Start running a new Nostromo flow and return its flow ID."
  [[run-addr run] conf {:nos/keys [store flow-chan]}]
  (try
    (let [job      (get-job conf (:job run))
          job-addr (-> run :job .toString)
          job-info (download-job-ipfs (:ipfsJob job) conf)
          flow     (cond-> (create-flow job-info run-addr run conf)
                     (int? (:job-timeout conf))
                     (assoc :expires (+ (:time run) (:job-timeout conf))))
          expired? (and (int? (:job-timeout conf))
                        (> (flow/current-time)
                           (+ (:time run) (:job-timeout conf))))
          flow-id  (:id flow)]
      (when expired?
        (throw (ex-info "Run has expired" {:run-time    (:time run)
                                           :job-timeout (:job-timeout conf)})))

      (log :info "Starting job" job-addr)
      (log :trace "Processing flow" flow-id)

      (<!! (kv/assoc store [:job->flow job-addr] flow-id))
      (go
        (<! (flow/save-flow flow store))
        (>! flow-chan [:trigger flow-id])
        flow-id))
    (catch Exception e
      (log :error "Error starting flow" e)
      (go
        (log :info "Quit run because of error" (.toString run-addr))
        (let [sig (quit-job conf (sol/public-key run-addr))]
          (<! (sol/await-tx< sig (:network conf)))
          nil)))))

(defn exit-work-loop!
  "Stop the main work loop for the system"
  [{:keys [nos/exit-chan]}]
  (put! exit-chan true))

(defn should-check-health?
  "Returns true if the timestamp is older than the time interval."
  [timestamp]
  (> (- (flow/current-time) timestamp) (* 60 15)))

(defn find-next-run
  "Find all assigned runs and return the first assigned to our
market. Returns a tuple of [run-address run-data]."
  [conf]
  (let [runs (find-my-runs conf)]
    (loop [[[run-addr run] & rst] runs]
      (when run-addr
        (let [job (get-job conf (:job run))]
          (prn (:market job) (:market conf))
          (if (.equals (:market job) (:market conf))
            [run-addr run]
            (recur rst)))))))

(defn work-loop
  "Main loop."
  [conf {:nos/keys [poll-delay exit-chan] :as system}]
  (go-loop [active-flow nil
            last-health-check (flow/current-time)
            healthy? true]
    (async/alt!
      ;; put anything on :exit-ch to stop the loop
      exit-chan (log :info "Work loop exited")
      ;; otherwise we will loop onwards with a timeout
      (timeout poll-delay)
      (cond
        (should-check-health? last-health-check)
        (let [[status health msgs] (healthy conf)]
          (log :info "Checking node health...")
          (case status
            :success (do
                       (log :info "Node is healthy")
                       (recur active-flow (flow/current-time) true))
            :error (do
                     (log :info (str "Node not healthy, waiting. Status:\n"
                                     (string/join "\n- " msgs)))
                     (recur nil (flow/current-time) false))))

        (not healthy?) (do
                         (log :info "Node not healthy, waiting.")
                         (recur nil last-health-check false))
        active-flow (do
                      (log :info "Checking progress of flow " active-flow)
                      (recur (<! (process-flow! active-flow conf system))
                             last-health-check true))
        :else
        (let [my-run (find-next-run conf)]
          (cond
            my-run (do
                     (log :info "Found claimed jobs to work on")
                     (recur (<! (start-flow-for-run! my-run conf system))
                            last-health-check true))

            (is-queued? conf) (do

                                (log :info "Waiting in the queue.")
                                (recur nil last-health-check true))

            :else (let [enter-sig (enter-market conf)]
                    (log :info "Entering the queue")
                    (when enter-sig
                      (<! (sol/await-tx< enter-sig (:network conf))))
                    (recur nil last-health-check true))))))))

(defn use-nosana
  [{:nos/keys [store flow-chan vault] :as system}]
  ;; Wait a bit for podman to boot
  (log :info "Waiting 5s for podman")
  (Thread/sleep 5000)
  (let [network    (:solana-network vault)
        market     (:nosana-market vault)
        conf       (make-config system)
        exit-ch    (chan)

        [status health msgs] (healthy conf)]

    (print-head
     ;; TODO: version from env
     "v0.3.19"
     (:address conf)
     (:network conf)
     (:market conf)
     (-> health :sol)
     (-> health :nos)
     (:nft conf)
     (-> health :nft))

    (case status
      :success (println "Node healthy. LFG.")
      :error   (println (str "\u001B[31mNode not healthy:\u001B[0m\n- "
                             (string/join "\n- " msgs))))

    ;; Add a shutdown hook, will be called when the JVM exits
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. (fn []
                (log :info "Shutting down...")
                (when (is-queued? conf)

                  ;; Exit the work loop first
                  (log :info "Exiting Queue")
                  ;; (exit-work-loop! system)
                  (async/put! exit-ch true)

                  ;; Then exit the market
                  (log :info "Trying to exit market")
                  ;; try exit market transaction, catch error
                  (when-let [sig (try (exit-market conf)
                                      (catch Exception e
                                        (log :error "Failed to exit market" e)))]
                    (log :info "Waiting for exit market transaction." sig)
                    ;; Await transaction
                    (<!! (sol/await-tx< sig (:network conf)))))
                (log :info "Shutdown complete"))))

    ;; put any value to `exit-ch` to cancel the `loop-ch`:
    ;; (async/put! exit-ch true)
    (-> system
        (assoc
         :nos/loop-chan
         (when (and (:start-job-loop? vault) (= :success status))
           (work-loop conf (merge  system
                                   {:nos/exit-chan  exit-ch
                                    :nos/poll-delay (:poll-delay-ms vault)})))
         :nos/exit-chan exit-ch
         :nos/poll-delay (:poll-delay-ms vault)
         :nos/solana-network (:network conf)
         :nos/programs (:programs conf))
        (update :system/stop conj #(put! exit-ch true)))))

(defmethod ig/halt-key! :nos/jobs
  [_ {:keys [exit-chan]}]
  (put! exit-chan true))
