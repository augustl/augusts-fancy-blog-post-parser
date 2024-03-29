(ns augusts-fancy-blog-post-parser.core
  (:require net.cgrand.tagsoup
            [clygments.core :as pygments])
  (:import [org.joda.time LocalDate]
           [org.joda.time.format DateTimeFormat]
           [java.nio.file Paths]))

(def date-formatter (DateTimeFormat/forPattern "yyyy.MM.dd"))
(def pretty-date-formatter (DateTimeFormat/forPattern "MMMM dd, yyyy"))

(defn parse-headers
  [header-lines]
  (->
   (into {} (map #((juxt (comp keyword first) second) (clojure.string/split % #": ?" 2)) header-lines))
   (update-in [:date] (fn [date] (LocalDate/parse date date-formatter)))))

(defn parse-raw-html-attrs
  [html-attrs]
  (if html-attrs
    (-> (net.cgrand.tagsoup/parser (java.io.StringReader. (str "<span" html-attrs ">")))
        first :content first :content first :attrs)))

(defn perform-highlight
  [lang code]
  (str "<code class=\"highlight\">"
       (clojure.string/trim (pygments/highlight code (or lang "text") :html {:nowrap true}))
       "</code>"))

(defn re-seq-with-pos
  [re str]
  (let [*matcher* (re-matcher re str)]
    (loop [match (re-find *matcher*)
           matches []]
      (if match
        (let [start (.start *matcher*)]
          (recur (re-find *matcher*)
                 (conj matches {:start start :end (+ start (count (first match))) :match match})))
        matches))))

(defn highlight-code
  [html]
  (loop [curr 0
         matches (map #(assoc % :highlighted
                              (perform-highlight
                               (:data-lang (parse-raw-html-attrs (nth (:match %) 1)))
                               (nth (:match %) 2)))
                      (re-seq-with-pos #"(?ms)\<code(.*?)\>\s*?(.*?)\s*?\<\/code\>" html))
         res []]
    (if (empty? matches)
      (clojure.string/join (conj res (subs html curr)))
      (let [match (first matches)]
        (recur
         (:end match)
         (rest matches)
         (conj res (subs html curr (:start match)) (:highlighted match)))))))

(defn parse-body
  [file]
  (with-open [r (clojure.java.io/reader file :encoding "UTF-8")]
    (->> (line-seq r)
         (drop-while (comp not clojure.string/blank?))
         (rest)
         (clojure.string/join "\n")
         (highlight-code))))

(defn remove-file-extension
  [path]
  (->> (clojure.string/split path #"\.")
       (butlast)
       (clojure.string/join ".")))

(defn parse
  [dir file]
  (with-open [r (clojure.java.io/reader file :encoding "UTF-8")]
    (let [relative-path (subs (.getPath file) (count (.getPath dir)))
          path-segments (->> (Paths/get relative-path (make-array String 0))
                             (.iterator)
                             (iterator-seq)
                             (map #(.toString %))
                             (vec))
          url (str
                "/"
                (clojure.string/join
                  "/"
                  (conj
                    (vec (butlast path-segments))
                    (remove-file-extension (last path-segments)))))
          headers (parse-headers (take-while (comp not clojure.string/blank?) (line-seq r)))]
      {:url url
       :headers headers
       :get-body (partial parse-body file)
       :id (clojure.string/replace url #"/" ":")
       :pretty-date (.print pretty-date-formatter (:date headers))})))

(defn get-posts
  [dir]
  (let [dir (clojure.java.io/as-file dir)]
    (->> (file-seq dir)
         (filter #(re-find #"\.html$" (.getPath %)))
         (map #(parse dir %))
         (sort-by #(get-in % [:headers :date]) #(.compareTo %2 %1)))))
