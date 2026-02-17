(ns tunarr.scheduler.curation.episode-tags
  "Heuristic-based detection of episodes that need special tags.

   Two-tier approach:
   - Tier 1 (deterministic): keyword/positional/seasonal detection, runs on all
     episodes without calling the LLM. Applied automatically during curation.
   - Tier 2 (LLM-assisted): only episodes flagged by Tier 1 are sent to tunabrain
     for refined tagging. This avoids the cost of per-episode LLM calls."
  (:require [clojure.string :as str]
            [tunarr.scheduler.media :as media]))

(def keyword-patterns
  "Map of substring patterns (matched against lowercased episode name + overview)
   to the tags they imply."
  {"christmas"    :christmas
   "xmas"         :christmas
   "holiday"      :holiday
   "halloween"    :halloween
   "thanksgiving" :thanksgiving
   "valentine"    :valentines
   "new year"     :new-years
   "finale"       :finale
   "pilot"        :pilot
   "premiere"     :premiere
   "clip show"    :clip-show
   "musical"      :musical
   "crossover"    :crossover
   "wedding"      :wedding
   "bottle episode" :bottle-episode})

(defn detect-keyword-tags
  "Check episode name and overview for special keyword matches.
   Returns a seq of keyword tags."
  [{:keys [::media/name ::media/overview]}]
  (let [text (str/lower-case (str name " " overview))]
    (keep (fn [[pattern tag]]
            (when (str/includes? text pattern) tag))
          keyword-patterns)))

(defn detect-positional-tags
  "Detect tags based on episode position within its season.
   `total-episodes-in-season` is optional; when provided, enables finale detection."
  [{:keys [::media/episode-number ::media/season-number]}
   & {:keys [total-episodes-in-season]}]
  (cond-> []
    (and (= 1 season-number) (= 1 episode-number))
    (conj :pilot)

    (= 1 episode-number)
    (conj :premiere)

    (and total-episodes-in-season
         (= episode-number total-episodes-in-season))
    (conj :finale)))

(defn detect-seasonal-tags
  "Detect tags based on original air date (premiere date)."
  [{:keys [::media/premiere]}]
  (when premiere
    (let [month (.getMonthValue premiere)]
      (cond-> []
        (= 12 month) (conj :holiday-season)
        (= 10 month) (conj :halloween-season)))))

(defn auto-tag-episode
  "Apply deterministic tags without LLM. Returns a set of tags.
   This is Tier 1 -- free, fast, runs on all episodes."
  [episode & {:keys [total-episodes-in-season]}]
  (set (concat (detect-keyword-tags episode)
               (detect-positional-tags episode
                                       :total-episodes-in-season total-episodes-in-season)
               (detect-seasonal-tags episode))))

(defn episode-needs-special-tags?
  "Returns truthy (the detected tags) if the episode is a candidate for
   Tier 2 LLM tagging -- i.e., it has keyword or seasonal markers suggesting
   it's a special episode."
  [episode]
  (seq (concat (detect-keyword-tags episode)
               (detect-seasonal-tags episode))))
