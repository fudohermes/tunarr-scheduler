(ns tunarr.scheduler.media.jellyfin-collection
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.instant :refer [read-instant-date]]
            [cemerick.url :as url]
            [taoensso.timbre :as log]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.collection :as collection]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]])
  (:import [java.time LocalDate ZoneId]))

(defn jellyfin-request [{api-key :api-key} url]
  (let [opts {:headers {"X-Emby-Token" api-key}}]
    (log/info "Fetching Jellyfin resources" {:url url})
    (-> (http/get url opts)
        :body
        (json/parse-string true))))

(defn build-url
  [base-url & {:keys [params path]}]
  (-> (url/url base-url path)
      (assoc :query params)
      str))

(def JELLYFIN_DEFAULT_FIELDS
  ["AirTime"
   "Id"
   "ProductionYear"
   "HasSubtitles"
   "PremiereDate"
   "Name"
   "Type"
   "OfficialRating"
   "CriticRating"
   "CommunityRating"
   "Tags"
   "Overview"
   "Genres"
   "Taglines"
   "ParentIndexNumber"
   "IndexNumber"
   "SeriesId"])

(defn transform-field
  [i in out f]
  (assoc i out (f (get i in))))

;; TODO: Implement iso->pretty if human-readable date formatting is needed

(defn normalize-rating
  "Most ratings are between 1 - 10, but some are between 1 - 100. If it's <10,
  assume it's 1-10, but if it's greater we should 'normalize' it to 1-10."
  [n]
  (if (and (number? n) (> n 10))
    (* n 0.1)
    n))

(defn parse-jellyfin-item [item]
  (letfn [(default [d] (fn [o] (or o d)))]
    (-> item
        (transform-field :Name ::media/name
                         identity)
        (transform-field :Overview ::media/overview
                         identity)
        (transform-field :Genres ::media/genres
                         (fn [genres] (map ->kebab-case-keyword genres)))
        (transform-field :CommunityRating ::media/community-rating
                         normalize-rating)
        (transform-field :CriticRating ::media/critic-rating
                         normalize-rating)
        (transform-field :OfficialRating ::media/rating
                         identity)
        (transform-field :Id ::media/id
                         identity)
        (transform-field :Type ::media/type
                         ->kebab-case-keyword)
        (transform-field :ProductionYear ::media/production-year
                         identity)
        (transform-field :Subtitles ::media/subtitles?
                         (fn [s] (or s false)))
        (transform-field :PremiereDate ::media/premiere
                         (fn [d] (or (some-> d
                                            (read-instant-date)
                                            (.toInstant)
                                            (.atZone (ZoneId/systemDefault))
                                            (.toLocalDate))
                                    (LocalDate/now))))
        (transform-field :Tags ::media/tags
                         (fn [tags] (->> (or tags [])
                                        (map ->kebab-case-keyword))))
        (transform-field :Taglines ::media/taglines
                         (default []))
        (transform-field :SeriesId ::media/parent-id
                         identity)
        (transform-field :ParentIndexNumber ::media/season-number
                         identity)
        (transform-field :IndexNumber ::media/episode-number
                         identity))))

(defn jellyfin:fetch-series-episodes
  "Fetch all episodes for a given series from Jellyfin."
  [{:keys [base-url] :as config} series-id library-id]
  (let [url (build-url base-url
                       :path (str "/Shows/" series-id "/Episodes")
                       :params {:Fields (str/join "," JELLYFIN_DEFAULT_FIELDS)})]
    (log/info "Fetching episodes for series" {:series-id series-id})
    (->> (jellyfin-request config url)
         :Items
         (map parse-jellyfin-item)
         (map (fn [ep]
                (assoc ep
                       ::media/type :episode
                       ::media/parent-id series-id
                       ::media/library-id library-id))))))

(defn jellyfin:fetch-library-items
  "Fetch all movies, series, and episodes from a Jellyfin library.
   Series are fetched first, then episodes for each series are fetched
   and appended. The result is ordered with series before their episodes."
  [{:keys [base-url libraries] :as config} library]
  (if-let [library-id (get libraries library)]
    (let [url (build-url base-url
                         :path   "/Items"
                         :params {:Recursive        true
                                  :SortBy           "SortName"
                                  :ParentId         library-id
                                  :IncludeItemTypes "Movie,Series"
                                  :Fields           (str/join "," JELLYFIN_DEFAULT_FIELDS)})
          top-level (->> (jellyfin-request config url)
                         :Items
                         (map parse-jellyfin-item)
                         (map (fn [m] (assoc m ::media/library-id library-id))))
          series-items (filter #(= :series (::media/type %)) top-level)
          episodes (mapcat (fn [series]
                             (try
                               (jellyfin:fetch-series-episodes config
                                                               (::media/id series)
                                                               library-id)
                               (catch Exception e
                                 (log/warn "Failed to fetch episodes for series"
                                           {:series (::media/name series)
                                            :error  (.getMessage e)})
                                 [])))
                           series-items)]
      (concat top-level episodes))
    (throw (ex-info (format "media library not found: %s" library)
                    {:library library}))))

(defn- ensure-episode-defaults
  "Episodes from Jellyfin may lack some fields that series/movies have.
   Fill in sensible defaults so they pass spec validation."
  [item]
  (if (= :episode (::media/type item))
    (cond-> item
      (nil? (::media/production-year item))
      (assoc ::media/production-year (or (some-> (::media/premiere item) (.getYear))
                                         (.getYear (LocalDate/now))))
      (nil? (::media/subtitles item))
      (assoc ::media/subtitles false)
      (nil? (::media/kid-friendly? item))
      (assoc ::media/kid-friendly? false)
      (nil? (::media/taglines item))
      (assoc ::media/taglines [])
      (nil? (::media/tags item))
      (assoc ::media/tags [])
      (nil? (::media/genres item))
      (assoc ::media/genres [])
      (nil? (::media/subtitles? item))
      (assoc ::media/subtitles? false))
    item))

(defrecord JellyfinMediaCollection [config]
  collection/MediaCollection
  (get-library-items [_ library]
    (for [md (jellyfin:fetch-library-items config library)
          :let [md (ensure-episode-defaults md)]]
      (if (s/invalid? (s/conform ::media/metadata md))
        (do (log/error (s/explain ::media/metadata md))
            (throw (ex-info "invalid metadata" {:metadata md :error (s/explain-data ::media/metadata md)})))
        md)))
  (close! [_] (log/info "closed jellyfin media collection")))

(s/def ::library-name keyword?)
(s/def ::library-id string?)
(s/def ::libraries (s/map-of ::library-name ::library-id))

(s/def ::collection-config
  (s/keys :req-un [::api-key ::base-url ::verbose ::libraries]))

(defmethod collection/initialize-collection! :jellyfin
  [config]
  (let [checked-config (s/conform ::collection-config config)]
    (if (s/invalid? checked-config)
      (throw (ex-info "invalid collection spec"
                      {:error (s/explain-data ::collection-config config)}))
      (->JellyfinMediaCollection config))))
