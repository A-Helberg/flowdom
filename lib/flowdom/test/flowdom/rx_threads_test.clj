(ns flowdom.rx-threads-test
  "JVM only: rx sources emitting from a second thread while the owning
  thread mounts, re-runs and unmounts. A deadlock here fails the test
  (with a thread dump) instead of hanging the suite."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [flowdom.core :as fd :refer [with-render snapshot]]
            [flowdom.rx :refer [rx ?]]
            [missionary.core :as m])
  (:import (java.lang.management ManagementFactory ThreadInfo)))

(defn- thread-dump []
  (->> (.dumpAllThreads (ManagementFactory/getThreadMXBean) true true)
       (map (fn [^ThreadInfo ti]
              (str "\"" (.getThreadName ti) "\" " (.getThreadState ti)
                   (when-let [l (.getLockName ti)] (str " on " l))
                   (when-let [o (.getLockOwnerName ti)] (str " held by \"" o "\""))
                   "\n"
                   (str/join "\n" (map #(str "    at " %) (take 40 (.getStackTrace ti)))))))
       (str/join "\n\n")))

(defn- within
  "Run `f` on another thread; its result, or ::deadlock (after printing a
  thread dump) when it hasn't finished in `ms`."
  [ms f]
  (let [fut (future (f))
        v   (deref fut ms ::deadlock)]
    (when (= v ::deadlock)
      (binding [*out* *err*]
        (println "flowdom: suspected deadlock, thread dump follows\n" (thread-dump))))
    v))

(defn- hammer!
  "Start a daemon thread writing to `a` in a tight loop; returns a stop fn.
  Daemon, so a thread stuck in a deadlock can't keep the JVM alive."
  [a]
  (let [stop? (atom false)
        t     (doto (Thread. ^Runnable (fn [] (while (not @stop?) (swap! a inc))))
                (.setDaemon true)
                (.start))]
    (fn [] (reset! stop? true) (.join t 1000))))

(def ^:private rounds 300)

(deftest unmount-while-another-thread-writes
  ;; teardown cancels upstream processes while a write on the other
  ;; thread is propagating up through the same rx blocks
  (is (= :done
         (within 20000
                 (fn []
                   (dotimes [_ rounds]
                     (let [n     (atom 0)
                           stop! (hammer! n)]
                       (try
                         (with-render [t [:div (rx [:section (rx [:p (rx (? n))])])]]
                           (snapshot t))
                         (finally (stop!)))))
                   :done)))))

(deftest dropping-a-branch-while-another-thread-writes
  ;; a re-run that stops reading a source cancels it while that source
  ;; is emitting on the other thread
  (is (= :done
         (within 20000
                 (fn []
                   (dotimes [_ rounds]
                     (let [n     (atom 0)
                           show? (atom true)
                           inner (rx (? n))
                           stop! (hammer! n)]
                       (try
                         (with-render [t [:div (rx (when (? show?) [:p (? inner)]))]]
                           (dotimes [_ 20] (swap! show? not))
                           (snapshot t))
                         (finally (stop!)))))
                   :done)))))

(deftest flow-source-emitting-from-another-thread
  ;; a missionary flow (not an atom) read by nested rx blocks, fed from
  ;; the other thread, unmounted mid-stream
  (is (= :done
         (within 20000
                 (fn []
                   (dotimes [_ rounds]
                     (let [n     (atom 0)
                           src   (m/ap (inc (m/?> (m/watch n))))
                           stop! (hammer! n)]
                       (try
                         (with-render [t [:div (rx [:section (rx [:p (? src)])])]]
                           (snapshot t))
                         (finally (stop!)))))
                   :done)))))

(deftest a-write-on-another-thread-still-lands
  (let [n (atom 0)]
    (with-render [t [:div (rx [:p (rx (? n))])]]
      @(future (dotimes [_ 1000] (swap! n inc)))
      (is (= [:div [:p 1000]] (fd/await t #(= [:div [:p 1000]] %)))))))
