# Phase 1 spike results

2026-11-28 · Companion to the [technical design](technical-design.md). Stack: Calcite 1.42.0, sqlite-jdbc 3.54.1.0, Java 27 (the project has since moved to Java 25). Spike 2 and the Top Values ties work were later dropped with Access SQL support.

All four spikes answered their question with **yes**. 43 spike tests passed against the Northstar sample database. Each compiled query was checked by running it on SQLite and comparing the rows with hand-written SQL. The design holds; the changes it needs are listed per spike and applied to the technical design.

| Spike | Question | Answer |
| --- | --- | --- |
| 1. Bind parameters | Can literals become `?` parameters after `SqlToRel`, with `SEARCH` expanded? | Yes, for IN, NOT IN, BETWEEN, NOT BETWEEN, LIKE, HAVING, booleans and dates; identical rows in all 8 cases |
| 2. Access criteria | Can Calcite's parser read Access criteria with a small pre-pass? | Yes, all 19 Access cell forms tried, and the result validates and runs. The pre-pass does more than planned |
| 3. Join tree | Can Access's ambiguous-outer-join rule be implemented exactly, with order-independent SQL? | Yes: Access's two ambiguous examples are refused, its three valid ones run correctly, and canvas order does not change the SQL |
| 4. SQLite semantics | Dates, case-insensitive text, Top Values with ties? | Yes, with three dialect adjustments |

## Spike 1: bind parameters after `SqlToRel`

- A `RexShuttle` applied to every node (through `RelHomogeneousShuttle`) replaces each literal (except NULL, `SYMBOL` and `SARG`) with a `RexDynamicParam` of the literal's type. It expands any `SEARCH` with `RexUtil.expandSearch` first.
- **Correction from the learning tests:** through the `Planner`, `SqlToRel` never produces `SEARCH`: IN lists arrive as `OR` of equalities and BETWEEN as `AND` of two comparisons. `SEARCH` only appears when `RelBuilder` simplification is used, so expanding it is a safeguard, not a required step.
- **Long IN lists:** with 20 or more values, `SqlToRel` turns IN into a join against `VALUES` by default. Setting `SqlToRelConverter.config().withInSubQueryThreshold(Integer.MAX_VALUE)` keeps it as a condition.
- `RelToSqlConverter` writes the parameters as `?`, and `SqlString.getDynamicParameters()` gives their textual order, which orders the bind values. HAVING parameters come after WHERE ones, as expected.
- Expanded forms appear in the target SQL: `IN (a, b)` becomes `x = ? OR x = ?`, and `BETWEEN` becomes `x >= ? AND x <= ?`. The canonical SQL view keeps the user's IN and BETWEEN; only the target SQL shows the expansion.
- Bind values come from the literal: `NlsString` as text, exact numerics as long or double (never `BigDecimal`, which the SQLite driver binds as TEXT), dates as ISO text.

## Spike 2: Access criteria through Calcite's parser

**Finding that changes the design:** Calcite cannot parse Access's `"double-quoted text"` together with `[bracketed]` identifiers. `CharLiteralStyle.BQ_DOUBLE` only takes effect in Calcite's BigQuery (backtick) lexical state, not with `Quoting.BRACKET`. So the pre-pass converts Access strings to SQL strings (`"O'Brien"` → `'O''Brien'`) before parsing.

The pre-pass, now fully specified, does only what Calcite has no syntax for:

| Access form | Becomes | Why |
| --- | --- | --- |
| `"text"` | `'text'` | See above |
| `#2026-11-01#`, `#11/15/2026#` | `DATE '2026-11-01'` | No Access date literal in Calcite |
| `&` | `\|\|` | Access concatenation |
| `Yes`, `No` | `TRUE`, `FALSE` | Access constants |
| `Date()`, `Len(…)` and other Access functions | `CURRENT_DATE`, `CHAR_LENGTH(…)` | Function mapping (to become an Access operator table) |
| A segment starting with an operator (`>= 100`, `Like …`, `In(…)`, `Between … And …`, `Is Null`) | Implied operand inserted | Done per top-level And/Or segment, so `> 100 And < 300` gives the column twice, as Access does; Between's own And is skipped |
| `Not value` | `<> value` | Access shorthand |
| A segment that is a bare value (`248`, `"O'Brien" Or "Smith"`) | `column = value` per segment | Access applies equality to each alternative |

After parsing, a `SqlShuttle` replaces the implied-operand placeholder with the column, and turns **unbracketed** bare words into text. That is Access's rule: `Completed` means `"Completed"`. `SqlIdentifier.isComponentQuoted` tells bracketed from bare, and names Calcite resolves as functions (`CURRENT_DATE`) are left alone. Parse errors carry line and column positions for underlining the cell.

## Spike 3: join graph to join tree

- **Rule, as Access states it:** an outer join may not point at a table that takes part in an inner join, and two outer joins may not point at the same table. Violations get Access's exact message.
- **Algorithm:**
  1. The tables no arrow points at form the core. They are joined by their inner lines, with every inner line to already-placed tables as one compound ON clause, so inner cycles work.
  2. Unconnected core parts are `CROSS JOIN`ed.
  3. Outer joins are then attached outward from placed tables, which supports chains such as `A --> B --> C`.
- Sorting the core and the attachment order by name makes the SQL independent of canvas order.
- The generated FROM clause is canonical Calcite SQL; `RelToSqlConverter` keeps it as written for SQLite.

## Spike 4: SQLite semantics

- **Dates:** typing SQLite date columns as Calcite `DATE` is the better choice. It gives full validation and date functions. Two adjustments make it work: the SQLite dialect writes date literals as plain ISO text (`'2026-11-01'`), because SQLite rejects `DATE '…'`, and dates are bound as ISO strings. Typing dates as `VARCHAR` also runs, but loses date validation. This reverses the earlier plan of mapping SQLite dates to character types.
- **Case-insensitive text, as Access:** after `SqlToRel`, a `RexShuttle` on Filter conditions wraps both sides of text comparisons and LIKE in `LOWER()`, after expanding `SEARCH`. It returns the same rows as a correctly-cased query. It is portable, at the cost of index use on those columns.
- **Top Values with ties:** Calcite does not parse `FETCH … WITH TIES`. The equivalent is built on the relational form with `RelBuilder`: `RANK() OVER (sort order)` filtered against `n`, or against `CEIL(n/100 × COUNT(*) OVER ())` for percentages. For "top 3 countries by orders" it returns five rows (USA, UK and the three countries tied on 2), as Access does. It needs window functions, which SQLite has had since 3.25.
- **NULL ordering:** `Frameworks.getPlanner` takes NULL ordering from `CalciteConnectionConfig.defaultNullCollation`, not from the validator config. Setting it to the dialect's collation (SQLite: LOW) stops Calcite writing `NULLS FIRST` that the user never asked for.

## What happens to the spike code

The code that would become production code is deleted, because it was not written test-first: the parameter replacer, the criteria pre-pass, the join-tree builder, the case-insensitivity shuttle and the Top Values builder. It is rebuilt test-first in phase 2, and the spike cases above become its first tests.

The tests that only check how Calcite, SQLite and the driver behave are kept as permanent **learning tests** in `learning-tests/`: 27 tests, all passing. They will catch a library upgrade that changes behaviour this design relies on:

| Test class | What it pins |
| --- | --- |
| `CalciteSqlGenerationLearningTest` (11) | IN and BETWEEN arrive without `SEARCH`; long IN lists become `VALUES` joins unless the threshold is raised; `expandSearch` works; `?` markers and their order; ordinal ORDER BY and its conformance fix; ANSI date literals and the unparse fix; RIGHT JOIN rewritten as LEFT; NULL ordering from the connection config; platform line separators; no `WITH TIES`; `RANK() OVER` runs on SQLite |
| `CalciteParserLearningTest` (10) | `[brackets]` quote identifiers; `"text"` does not parse with bracket quoting; bare words are unquoted identifiers; `CURRENT_DATE` parses as an identifier; `Date()` and `#date#` do not parse; operators parse once the column is in place; parse errors carry positions |
| `SqliteDriverLearningTest` (6) | `BigDecimal` is bound as TEXT and breaks aggregate comparisons; SQLite rejects `DATE '…'` and compares ISO text dates; `=` is case-sensitive while LIKE is not for ASCII; the bundled SQLite has window functions and RIGHT JOIN; read-only connections refuse writes |
