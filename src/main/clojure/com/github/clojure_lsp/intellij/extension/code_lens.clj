(ns com.github.clojure-lsp.intellij.extension.code-lens
  (:gen-class
   :name com.github.clojure_lsp.intellij.extension.CodeLensProvider
   :implements [com.intellij.codeInsight.hints.InlayHintsProvider])
  (:require
   [com.github.clojure-lsp.intellij.action.references :as action.references]
   [com.github.clojure-lsp.intellij.db :as db]
   [com.github.clojure-lsp.intellij.editor :as editor]
   [com.github.clojure-lsp.intellij.lsp-client :as lsp-client]
   [com.rpl.proxy-plus :refer [proxy+]])
  (:import
   [com.github.clojure_lsp.intellij ClojureLanguage]
   [com.intellij.codeInsight.hints
    FactoryInlayHintsCollector
    ImmediateConfigurable
    InlayGroup
    InlayHintsSink
    InlayPresentationFactory$ClickListener
    InlayPresentationFactory$HoverListener
    NoSettings
    SettingsKey]
   [com.intellij.codeInsight.hints.presentation ChangeOnHoverPresentation PresentationFactory]
   [com.intellij.lang Language]
   [com.intellij.openapi.editor Editor]
   [javax.swing JPanel]
   [kotlin.jvm.functions Function0 Function1]))

(set! *warn-on-reflection* true)

(defn -isLanguageSupported [_ ^Language language]
  (instance? ClojureLanguage language))

(defn -getName [_]
  "LSP code lens")

(defn -getDescription [_]
  "Clojure LSP code lens")

(defn -getPreviewText [_]
  "fooo")

(defn -getKey [_] (SettingsKey. "LSP code lens"))

(defn -createSettings [_] (NoSettings.))

(defn -getGroup [_] InlayGroup/OTHER_GROUP)

(defn -isVisibleInSettings [_] true)

(defn -createConfigurable [_ _]
  (reify
    ImmediateConfigurable
    (createComponent [_ _] (JPanel.))))

(defn ^:private handle-command [^Editor editor command arguments]
  (case command
    "code-lens-references"
    (let [[line character] (rest arguments)]
      (action.references/show-references editor (dec line) (dec character)))))

(set! *warn-on-reflection* false)
(defn ^:private code-lens-presentation
  [^PresentationFactory factory title on-click-fn]
  (let [base (.text factory title)
        hover-button (.roundWithBackgroundAndSmallInset
                      factory
                      (.getFirst
                       (.button
                        factory
                        base
                        base
                        (reify InlayPresentationFactory$ClickListener
                          (onClick [_ _ _] (on-click-fn)))
                        (reify InlayPresentationFactory$HoverListener
                          (onHover [_ _ _])
                          (onHoverFinished [_]))
                        false)))]
    (ChangeOnHoverPresentation. (.inset factory
                                        base
                                        3 3 6 0)
                                (reify Function0 (invoke [_] hover-button))
                                (reify Function1 (invoke [_ _] true)))))
(set! *warn-on-reflection* true)

(defn -getCollectorFor [_ _file ^Editor editor _settings ^InlayHintsSink sink]
  (when-let [client (and (identical? :connected (:status @db/db*))
                         (:client @db/db*))]
    ;; For some reason `(PresentationFactory. editor)` does not work
    (let [lens-added* (atom false)]
      (proxy+ [editor]
        FactoryInlayHintsCollector
        (collect [^FactoryInlayHintsCollector this _ _ _]
                 (when-not @lens-added*
                   (let [document (.getDocument editor)
                         code-lens @(lsp-client/request! client [:textDocument/codeLens
                                                                 {:text-document {:uri (editor/editor->uri editor)}}])
                         factory (.getFactory ^FactoryInlayHintsCollector this)]
                     (doseq [code-len code-lens]
                       (let [{:keys [range] {:keys [title command arguments]} :command} @(lsp-client/request! client [:codeLens/resolve code-len])]
                         (.addInlineElement sink
                                            (editor/position->point (:start range) document)
                                            true
                                            (code-lens-presentation
                                             factory
                                             title
                                             (fn onClick []
                                               (handle-command editor command arguments)))
                                            true)))
                     (reset! lens-added* true)))
                 false)))))
