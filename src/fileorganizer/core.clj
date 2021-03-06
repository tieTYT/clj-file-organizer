(ns fileorganizer.core
  (:require [fileorganizer.document-filter :refer [listen-doc-filter ignore-doc-filter set-document-filter]])
  (:require [seesaw.core :refer :all])
  (:require [clojure.string :as string])
  (:require [clojure.pprint :refer [pprint]])
  (:require [seesaw.chooser :refer :all])
  (:require [seesaw.keymap :refer [map-key]])
  (:require [me.raynes.fs :refer [rename delete base-name delete-dir]])
  (:require [seesaw.pref :refer [preferences-node preference-atom]])
  (:import [javax.swing JFileChooser KeyStroke JComponent UIManager])
  (:import [javax.swing.text DocumentFilter])
  (:import [java.awt.event KeyEvent])
  (:import [java.awt Desktop Component])
  (:import [java.io File])
  )

(native!)

(def shortcut-destination-map (atom {"Delete" "\"Reserved\""
                                     "Ctrl Z" "\"Reserved\""}))

(def actions (atom []))

(defn clear-input-map [input-map key-stroke]
  (when input-map
    (.remove input-map key-stroke)
    (clear-input-map (.getParent input-map) key-stroke)))

(defn for-each-component [f root]
  (when root
    (f root)
    (dorun
      (map #(for-each-component f %)  (.getComponents root)))))

(defn clear-input-map-of [component key-stroke]
  (for-each-component (fn [c]
                        (when (instance? javax.swing.JComponent c)
                          (clear-input-map (.getInputMap c JComponent/WHEN_ANCESTOR_OF_FOCUSED_COMPONENT) key-stroke)
                          (clear-input-map (.getInputMap c JComponent/WHEN_IN_FOCUSED_WINDOW) key-stroke)
                          (clear-input-map (.getInputMap c JComponent/WHEN_FOCUSED) key-stroke))) component))


(defn file-chooser [current-dir]
  (UIManager/put "FileChooser.readOnly" true)
  (doto (JFileChooser. current-dir)
    (.. getActionMap (get "viewTypeDetails") (actionPerformed nil))))

(def main-frame (frame :title "File Organizer"))

(defn open-file [f]
  (.open (Desktop/getDesktop) f))

(defn my-file-filter [f]
  (let [input (.getAbsolutePath f)
        sources (reduce conj #{} (map #(.getAbsolutePath (nth % 1)) @actions))]
    (not (sources input))))

(let [start-dir (preference-atom "main-fc-start-dir")]
  (def fc (doto (file-chooser @start-dir)
            (.setAcceptAllFileFilterUsed false)
            (config! :filters [(file-filter "All Files" my-file-filter)])
            (config! :selection-mode :files-and-dirs)
            (.setControlButtonsAreShown false)
            (.setMultiSelectionEnabled true)
            (listen :property-change (fn [e] (when (= JFileChooser/DIRECTORY_CHANGED_PROPERTY (.getPropertyName e))
                                               (reset! start-dir (-> e
                                                                   .getNewValue
                                                                   .getAbsolutePath)))))
            (listen :action (fn [e] (when (= (.getActionCommand e) "ApproveSelection")
                                      (open-file (.getSelectedFile fc))))))))

(defn refresh-fc []
  (doto fc
    (.rescanCurrentDirectory)
    (.setSelectedFile nil)
    (.setSelectedFiles nil)))

(def open-button (button :text "Open"))

(defn open-action [& e]
  (when-let [f (.getSelectedFile fc)]
    (when (.isFile f)
      (open-file (.getSelectedFile fc)))
    (when (.isDirectory f)
      (.setCurrentDirectory fc (.getSelectedFile fc)))
    ))

(listen open-button :action open-action)

(def delete-button (button :text "Delete"))

(defn delete-file-action []
  (when-let [selected-file (.getSelectedFile fc)]
    (swap! actions conj [:delete selected-file])
    (refresh-fc)))

(listen delete-button :action (fn [e] (delete-file-action)))

(defn undo-action []
  (when-not (empty? @actions)
    (swap! actions pop)
    (refresh-fc)))

(def undo-button (button :text "Undo"))

(listen undo-button :action (fn [e] (undo-action)))

(defn set-shortcut-text [text to]
  (doto text
    (set-document-filter listen-doc-filter)
    (config! :text to)
    (set-document-filter ignore-doc-filter)))

(defn shortcut-listener [text e]
  (let [modifier (KeyEvent/getKeyModifiersText (.getModifiers e))
        code (.getKeyCode e)
        key-text (KeyEvent/getKeyText code)
        modifier-pressed (some identity (map #(= code %) [KeyEvent/VK_CONTROL KeyEvent/VK_ALT KeyEvent/VK_SHIFT]))]
    (when-not modifier-pressed
      (set-shortcut-text text (str (KeyStroke/getKeyStrokeForEvent e))))))

(defn left-align [c]
  (doto c (.setAlignmentX Component/LEFT_ALIGNMENT)))

(defn make-shortcut-text []
  (doto (text :listen [:key-pressed #(shortcut-listener (.getSource %) %)])
    (set-document-filter ignore-doc-filter)))

(map-key main-frame "DELETE" (fn [e] (delete-file-action)) :scope :global)
(map-key main-frame "ctrl Z" (fn [e] (undo-action)) :scope :global)
(map-key main-frame "ENTER" (fn [e] (open-action)) :scope :global)

(defn move-file-action [destination]
  {:pre [destination]}
  (when-let [selected-file (.getSelectedFile fc)]
    (swap! actions conj [:move selected-file destination])
    (refresh-fc)))

(defn add-shortcut-and-destination [new-shortcut-destination-map shortcut-label shortcut-string destination-button selected-path]
  (when (not= "<Unset>" shortcut-string selected-path)
    (reset! shortcut-destination-map new-shortcut-destination-map)
    (config! shortcut-label :text shortcut-string)
    (config! destination-button :text selected-path)
    (map-key main-frame shortcut-string (fn [e] (move-file-action (File. selected-path))) :scope :global)
    (clear-input-map-of fc (KeyStroke/getKeyStroke shortcut-string))))

(defn shortcut-ok-button-action [dialog fc previous-shortcut-destination-map shortcut-label shortcut-text destination-button error-label]
  (let [selected-file (.getSelectedFile fc)
        shortcut-string (config shortcut-text :text)]
    (cond
      (not selected-file) (config! error-label :text "You must choose a directory")
      (empty? shortcut-string) (config! error-label :text "You must choose a shortcut")
      (get previous-shortcut-destination-map shortcut-string) (config! error-label :text (str "This shortcut is already bound to " (get previous-shortcut-destination-map shortcut-string)))
      :else (do
              (dispose! dialog)
              (add-shortcut-and-destination (assoc previous-shortcut-destination-map shortcut-string (.getAbsolutePath selected-file)) shortcut-label shortcut-string destination-button (.getAbsolutePath selected-file))))))

(defn destination-shortcut-dialog [destination-button shortcut-label]
  (let [d (custom-dialog :title "Choose a Shortcut" :modal? true :parent main-frame)
        previous-path (config destination-button :text)
        fc (doto (file-chooser (if (= "<Unset>" previous-path)
                                        nil
                                        (File. previous-path)))
             (.setControlButtonsAreShown false)
             (config! :selection-mode :dirs-only))
        st (make-shortcut-text)
        shortcut-panel (horizontal-panel :items ["Shortcut:" st])
        error-label (label :foreground :red)
        shortcut-text (if (= (config shortcut-label :text) "<Unset>") nil (config shortcut-label :text))
        frame-buttons (horizontal-panel :items [(button :text "OK"
                                                        :listen [:action (fn [e] (shortcut-ok-button-action d fc (dissoc @shortcut-destination-map shortcut-text) shortcut-label st destination-button error-label))])
                                                (button :text "Cancel"
                                                        :listen [:action (fn [e] (dispose! d))])])
        left-aligned-components (map left-align (list fc shortcut-panel frame-buttons error-label))]

    (config! d :content (vertical-panel :items left-aligned-components))
    (-> d pack! show!)))

(def num-of-shortcuts 15)

(def shortcut-items (map (fn [_] (label)) (range num-of-shortcuts)))

(def shortcuts (grid-panel :columns 1 :items (conj shortcut-items "Shortcuts")))

(defn configure-from-preferences [component pref-atom]
  (config! component :text @pref-atom)
  (listen component :property-change (fn [e]
                                       (when (= "text" (.getPropertyName e))
                                         (reset! pref-atom (config component :text))))))

(defn destination [my-index]
  (let [destination-button (button)
        shortcut-label (nth shortcut-items my-index)
        shortcut-pref (preference-atom (str "shortcut-pref-" my-index) "<Unset>")
        destination-pref (preference-atom (str "destination-pref-" my-index) "<Unset>")]
    (configure-from-preferences shortcut-label shortcut-pref)
    (configure-from-preferences destination-button destination-pref)
    (listen destination-button :action (fn [e] (destination-shortcut-dialog destination-button shortcut-label)))
    (add-shortcut-and-destination (assoc @shortcut-destination-map @shortcut-pref @destination-pref) shortcut-label @shortcut-pref destination-button @destination-pref)
    destination-button))

(def destination-items (map (fn [x] (destination x)) (range num-of-shortcuts)))

(def destinations (grid-panel :columns 1 :items (conj destination-items "Destinations")))

(def destination-shortcuts (horizontal-panel :items
                                             [(left-right-split
                                                destinations
                                                shortcuts
                                                :divider-location 2/3)]))

(defmulti action-as-string (fn [action] (nth action 0)))

(defmethod action-as-string :delete [[_ f]]
  (str "Deleting: " (.getAbsolutePath f)))

(defmethod action-as-string :move [[_ f d]]
  (str "Moving: " (.getAbsolutePath f) " to " (.getAbsolutePath d)))

(defn deletes-as-string [actions]
  (let [deletes (filter (comp (partial = :delete) first) actions)]
    (when-not (empty? deletes)
      (str
        (->> deletes
          (map #(-> % second .getName))
          (cons "Deletes:")
          (string/join "\n"))
        "\n\n"))))

(defn moves-as-string [actions]
  (let [moves-by-destination (->> actions
                               (filter (comp (partial = :move) first))
                               (group-by (comp first next next))) ;group by destination directory
        moves-by-destination (for [[key val] moves-by-destination] [(.getName key) (map #(-> % second .getName) val)])
        moves-by-destination (for [[key val] moves-by-destination] (str "Moved to " key ":\n    " (string/join "\n    " val)))
        ]
    (string/join "\n\n" moves-by-destination)))

(defn actions-as-string [actions]
  (str
    (deletes-as-string actions)
    (moves-as-string actions)
  ))


(defmulti run-action (fn [action] (nth action 0)))

(defmethod run-action :delete [[_ f]]
  (cond
    (.isDirectory f) (when-not (delete-dir f) (alert (str "Could not delete file " f)))
    :else (when-not (delete f) (alert (str "Could not delete file " f)))))

(defmethod run-action :move [[_ f d]]
  (if-not (rename f (File. d (base-name f)))
    (alert (str "Could not move " f " to " d))))

(defn commit-changes-action [e]
  (dorun (map run-action @actions))
  (reset! actions []))

(defn show-changes-action [e]
  (-> (dialog :content (if-let [as (not-empty @actions)] (text :multi-line? true :text (actions-as-string as)) "NO CHANGES!")
              :option-type :ok-cancel
              :success-fn commit-changes-action)
    pack!
    show!))

(config! main-frame :content (vertical-panel :items [fc
                                            (horizontal-panel :items [open-button delete-button])
                                            undo-button
                                            (separator)
                                            destination-shortcuts
                                            (separator)
                                            (button :text "Commit changes" :listen [:action show-changes-action])]))

(-> main-frame pack! show!)

(defn -main [& args]) ;this doesn't do anything, loading the namespace does everything for us