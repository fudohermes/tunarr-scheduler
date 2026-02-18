(ns tunarr.scheduler.curation.episode-tags-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.curation.episode-tags :as episode-tags]
            [tunarr.scheduler.media :as media])
  (:import [java.time LocalDate]))

;; --- Keyword detection tests ---

(deftest detect-keyword-tags-christmas-test
  (testing "detects Christmas episode from name"
    (let [ep {::media/name "The One with the Holiday Armadillo"
              ::media/overview "It's Christmas and Ross wants to teach Ben about Hanukkah."}]
      (is (contains? (set (episode-tags/detect-keyword-tags ep)) :christmas)))))

(deftest detect-keyword-tags-xmas-test
  (testing "detects Christmas episode from xmas in overview"
    (let [ep {::media/name "A Special Episode"
              ::media/overview "The gang gathers for an xmas party."}]
      (is (contains? (set (episode-tags/detect-keyword-tags ep)) :christmas)))))

(deftest detect-keyword-tags-halloween-test
  (testing "detects Halloween episode"
    (let [ep {::media/name "Halloween Heist"
              ::media/overview "The annual Halloween heist is on."}]
      (is (contains? (set (episode-tags/detect-keyword-tags ep)) :halloween)))))

(deftest detect-keyword-tags-finale-test
  (testing "detects finale from name"
    (let [ep {::media/name "The Last One"
              ::media/overview "The series finale."}]
      (is (contains? (set (episode-tags/detect-keyword-tags ep)) :finale)))))

(deftest detect-keyword-tags-wedding-test
  (testing "detects wedding episode"
    (let [ep {::media/name "The One with Ross's Wedding"
              ::media/overview "Ross gets married in London."}]
      (is (contains? (set (episode-tags/detect-keyword-tags ep)) :wedding)))))

(deftest detect-keyword-tags-musical-test
  (testing "detects musical episode"
    (let [ep {::media/name "My Musical"
              ::media/overview "The hospital becomes a musical."}]
      (is (contains? (set (episode-tags/detect-keyword-tags ep)) :musical)))))

(deftest detect-keyword-tags-unremarkable-test
  (testing "returns empty for unremarkable episode"
    (let [ep {::media/name "The One Where They All Turn Thirty"
              ::media/overview "Each friend turns thirty."}]
      (is (empty? (episode-tags/detect-keyword-tags ep))))))

(deftest detect-keyword-tags-multiple-test
  (testing "detects multiple keywords in same episode"
    (let [ep {::media/name "Christmas Wedding Spectacular"
              ::media/overview "A holiday wedding."}]
      (let [tags (set (episode-tags/detect-keyword-tags ep))]
        (is (contains? tags :christmas))
        (is (contains? tags :wedding))
        (is (contains? tags :holiday))))))

;; --- Positional detection tests ---

(deftest detect-positional-tags-pilot-test
  (testing "first episode of first season is tagged pilot"
    (let [ep {::media/season-number 1 ::media/episode-number 1}
          tags (set (episode-tags/detect-positional-tags ep))]
      (is (contains? tags :pilot))
      (is (contains? tags :premiere)))))

(deftest detect-positional-tags-premiere-test
  (testing "first episode of any season is tagged premiere"
    (let [ep {::media/season-number 3 ::media/episode-number 1}
          tags (set (episode-tags/detect-positional-tags ep))]
      (is (contains? tags :premiere))
      (is (not (contains? tags :pilot))))))

(deftest detect-positional-tags-finale-test
  (testing "last episode in season is tagged finale when total is known"
    (let [ep {::media/season-number 3 ::media/episode-number 24}
          tags (set (episode-tags/detect-positional-tags ep
                     :total-episodes-in-season 24))]
      (is (contains? tags :finale)))))

(deftest detect-positional-tags-no-finale-without-total-test
  (testing "finale not detected without total-episodes-in-season"
    (let [ep {::media/season-number 3 ::media/episode-number 24}
          tags (set (episode-tags/detect-positional-tags ep))]
      (is (not (contains? tags :finale))))))

(deftest detect-positional-tags-middle-episode-test
  (testing "middle episode has no positional tags"
    (let [ep {::media/season-number 2 ::media/episode-number 12}
          tags (episode-tags/detect-positional-tags ep
                 :total-episodes-in-season 24)]
      (is (empty? tags)))))

;; --- Seasonal detection tests ---

(deftest detect-seasonal-tags-december-test
  (testing "December premiere gets holiday-season tag"
    (let [ep {::media/premiere (LocalDate/of 1994 12 15)}
          tags (set (episode-tags/detect-seasonal-tags ep))]
      (is (contains? tags :holiday-season)))))

(deftest detect-seasonal-tags-october-test
  (testing "October premiere gets halloween-season tag"
    (let [ep {::media/premiere (LocalDate/of 1995 10 26)}
          tags (set (episode-tags/detect-seasonal-tags ep))]
      (is (contains? tags :halloween-season)))))

(deftest detect-seasonal-tags-june-test
  (testing "June premiere gets no seasonal tags"
    (let [ep {::media/premiere (LocalDate/of 1996 6 15)}
          tags (episode-tags/detect-seasonal-tags ep)]
      (is (empty? tags)))))

(deftest detect-seasonal-tags-nil-premiere-test
  (testing "nil premiere returns nil"
    (is (nil? (episode-tags/detect-seasonal-tags {::media/premiere nil})))))

;; --- auto-tag-episode integration tests ---

(deftest auto-tag-episode-combines-all-detectors-test
  (testing "auto-tag-episode combines keyword, positional, and seasonal tags"
    (let [ep {::media/name "Christmas Pilot"
              ::media/overview "The very first episode"
              ::media/season-number 1
              ::media/episode-number 1
              ::media/premiere (LocalDate/of 1994 12 22)}
          tags (episode-tags/auto-tag-episode ep)]
      (is (contains? tags :christmas))
      (is (contains? tags :pilot))
      (is (contains? tags :premiere))
      (is (contains? tags :holiday-season)))))

(deftest auto-tag-episode-empty-for-unremarkable-test
  (testing "unremarkable mid-season episode gets no auto-tags"
    (let [ep {::media/name "The One with the Routine"
              ::media/overview "Monica and Ross do a routine."
              ::media/season-number 6
              ::media/episode-number 10
              ::media/premiere (LocalDate/of 1999 9 30)}
          tags (episode-tags/auto-tag-episode ep)]
      (is (empty? tags)))))

;; --- episode-needs-special-tags? tests ---

(deftest episode-needs-special-tags-positive-test
  (testing "christmas episode needs special tags"
    (let [ep {::media/name "A Very Merry Christmas"
              ::media/overview "Holiday cheer."}]
      (is (episode-tags/episode-needs-special-tags? ep)))))

(deftest episode-needs-special-tags-negative-test
  (testing "unremarkable episode does not need special tags"
    (let [ep {::media/name "The Contest"
              ::media/overview "A bet among friends."}]
      (is (not (episode-tags/episode-needs-special-tags? ep))))))
