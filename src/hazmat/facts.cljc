(ns hazmat.facts
  "HazardousWaste classification catalog — the ONLY hazard-classification
  provenance classes the HazardousWasteGovernor will accept as a citation
  for a collection's hazardous-waste-stream classification (mirrors
  `cloud-itonami-isic-6311`'s `marketdata.facts` discipline: honesty over
  coverage). This actor collects, routes and coordinates treatment/disposal
  for HAZARDOUS waste streams only; the classification basis is what lets
  the hazard-gate (`hazmat.policy`) tell 'declared hazardous, on this basis'
  apart from 'the LLM guessed'.

  Also carries the real regulatory frameworks that define what counts as
  hazardous, and what treatment/disposal methods are licensed — R0 scope:
  3 real, citable frameworks, not a claim of global coverage. Extend only
  by appending a real, citable framework or classification-basis class,
  never fabricate either.")

(def hazard-classification-catalog
  "Hazard classification source frameworks. Each entry:
  {:id :name :class :jurisdiction :basis :url}. `:class` is the value that
  must appear in a collection request's `:source :class` for the
  hazard-classification-gate to accept it as grounded."
  [{:id :us-rcra-hazardous-waste-listing
    :name "US EPA Resource Conservation and Recovery Act (RCRA), 40 CFR Part 261 hazardous waste identification"
    :class :collector-visual-inspection :jurisdiction :usa
    :basis :regulatory-framework
    :url "https://www.epa.gov/hw/how-hazardous-waste-regulated"}
   {:id :eu-waste-framework-directive
    :name "EU Waste Framework Directive 2008/98/EC, Annex III hazardous properties (HP1-HP15)"
    :class :facility-intake-scan :jurisdiction :eu
    :basis :regulatory-framework
    :url "https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32008L0098"}
   {:id :jpn-waste-management-act
    :name "廃棄物の処理及び清掃に関する法律(廃棄物処理法)特別管理産業廃棄物の指定"
    :class :generator-self-declaration :jurisdiction :jpn
    :basis :regulatory-framework
    :url "https://www.env.go.jp/recycle/waste/"}])

(def disposal-treatment-catalog
  "Licensed disposal/treatment methods. Each entry:
  {:id :name :method :jurisdiction :compliance-standard :url}. The actor
  proposes coordination with these, never issues the disposal certification
  itself (that remains the licensed disposal operator's exclusive authority)."
  [{:id :us-epa-incineration
    :name "US EPA-regulated incineration with air emission controls"
    :method :incineration :jurisdiction :usa
    :compliance-standard :epa-40cfr264
    :url "https://www.epa.gov/hwgenerators/managing-hazardous-waste-epa-rcra-requirements"}
   {:id :us-epa-landfill
    :name "US EPA-regulated hazardous waste landfill"
    :method :secure-landfill :jurisdiction :usa
    :compliance-standard :epa-40cfr264
    :url "https://www.epa.gov/hwgenerators/managing-hazardous-waste-epa-rcra-requirements"}
   {:id :eu-incineration
    :name "EU-regulated incineration Directive 2000/76/EC"
    :method :incineration :jurisdiction :eu
    :compliance-standard :eu-2000-76-ec
    :url "https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32000L0076"}
   {:id :eu-landfill
    :name "EU Landfill Directive 1999/31/EC for hazardous waste"
    :method :secure-landfill :jurisdiction :eu
    :compliance-standard :eu-1999-31-ec
    :url "https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:31999L0031"}
   {:id :jpn-incineration
    :name "廃棄物処理法に基づく産業廃棄物焼却施設"
    :method :incineration :jurisdiction :jpn
    :compliance-standard :jpn-waste-law-facilities
    :url "https://www.env.go.jp/recycle/waste/"}])

(def allowed-hazard-classification-classes
  "The set of `:source :class` values the hazard-classification-gate will
  accept. A closed set — a class not in `hazard-classification-catalog`
  (e.g. :inference, :unverified-guess) must be rejected, not silently
  accepted because it looks like a keyword."
  (into #{} (map :class hazard-classification-catalog)))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers — never
  overstate ('全世界の廃棄物規制' in prose, 3 frameworks in fact)."
  []
  {:hazard-classification-framework-count (count hazard-classification-catalog)
   :disposal-treatment-method-count (count disposal-treatment-catalog)
   :jurisdictions (into (sorted-set)
                        (concat (map :jurisdiction hazard-classification-catalog)
                                (map :jurisdiction disposal-treatment-catalog)))
   :note (str "R0 scope: 3 real hazard-classification frameworks (US RCRA, "
              "EU Waste Framework Directive, Japan 廃棄物処理法) + 4 licensed "
              "disposal/treatment methods, grounding 3 classification-basis "
              "source classes. Extend only by appending a real, citable "
              "framework — never fabricate one. Actor proposes coordination "
              "with disposal facilities only (does not issue disposal "
              "certifications).")})

(defn class-allowed? [source-class]
  (contains? allowed-hazard-classification-classes source-class))
