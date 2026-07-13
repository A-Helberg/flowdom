# solidclj-docs

A docs/tutorial shell for [solidclj](../solidclj) apps: sidebar
navigation, hash routing, and code-left/live-result-right example
blocks. See `example/docs/examples-pattern.md` at the repo root for the
pattern it implements.

```clojure
[docs/app {:title          "mylib"
           :subtitle       "guide"
           :sections       pages/sections
           :sidebar-footer my-ns/toggle-component}]
```

## Tailwind setup (required)

The shell styles itself with Tailwind utility classes, but Tailwind
only generates classes it *sees* as text in the files it scans — your
build won't scan this library's source by default, and the shell will
render unstyled.

Two requirements in your `tailwind.css`:

1. the typography plugin (the shell uses `prose`):

   ```css
   @plugin "@tailwindcss/typography";
   ```

2. an `@source` covering this lib's classes. Pick one:

   **Monorepo / `:local/root` dep** — scan the source directly:

   ```css
   @source "../path/to/solidclj-docs/src";
   ```

   **Git or jar dep** — the lib ships a class manifest on the
   classpath at `solidclj/docs/classes.txt`. Copy it into your repo
   once per lib upgrade:

   ```sh
   clojure -M -e '(spit "solidclj-docs.classes.txt" (slurp (clojure.java.io/resource "solidclj/docs/classes.txt")))'
   ```

   ```css
   @source "./solidclj-docs.classes.txt";
   ```

### How the manifest stays honest

`clojure -M:gen` (run in this directory) regenerates the manifest from
every string literal in `src/`, and a test in the example app inlines
the lib sources at compile time and fails if the two ever diverge — so
a released manifest cannot silently miss a class. It is deliberately
over-inclusive (docstring prose and all): Tailwind ignores tokens that
aren't utilities, while under-inclusion is exactly the unstyled-client
bug this exists to prevent.
