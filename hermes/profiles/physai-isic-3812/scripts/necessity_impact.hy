"Launcher only. Hermes cron runs just .py/.sh and AGENTS.md forbids a new .sh,\nso this 5-line launcher (same shape as physai_measure.py) starts the probe,\nwhich is necessity_impact.cljk (kotoba, run by kbb). No logic lives here."
(import os)
(import subprocess)
(setv HERE ((. os.path dirname) ((. os.path abspath) __file__)))
(setv KBB ((. os.environ get) "KBB" "/opt/homebrew/bin/kbb"))
(when (= __name__ "__main__") (raise (SystemExit (subprocess.call [KBB "--backend" "sci" ((. os.path join) HERE "necessity_impact.cljk")]))))
