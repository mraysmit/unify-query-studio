# Access query designer reference

2026-11-28 · Exported from the "Access reference" tab of the [claude.ai technical design document](https://claude.ai/artifact/79H6vGBVuQNCb1gPdFS71T). Used by the [technical design](technical-design.md) and the [UI concept specification](ui-concept-specification.md).

Query Studio copies the Microsoft Access query designer's workflow, so this document records how Access actually behaves, with the SQL it writes.

**Scope (decided 2026-11-28):** Query Studio does not support Microsoft Access SQL. Cells hold standard SQL, and results are what the target database returns. The parts of this reference about Access SQL syntax and semantics (criteria cell syntax, `"text"`, `#dates#`, case-insensitive comparison, Top Values ties) are kept as background only. The designer behaviour (joins, the Total row, criteria rows, naming, Design view and SQL view) is still followed. Facts come from Microsoft documentation and Access MVP sources opened on 2026-11-28; anything marked *unverified* was not confirmed by an opened page.

## Data sources and joins

| Behaviour | What Access does | Source |
| --- | --- | --- |
| Sources | Tables and saved queries can be added, alone or together, and joined the same way | [Join tables and queries](https://support.microsoft.com/en-us/office/join-tables-and-queries-3f5838bd-24a0-4832-9bc1-07061a1478f6) |
| Same table twice | Second instance is named `Employees_1`; SQL `Employees AS Employees_1`; renamed through the field list's Alias property | [Create a simple select query](https://support.microsoft.com/en-us/access/create-a-simple-select-query), [Query properties](https://learn.microsoft.com/en-us/previous-versions/troubleshoot/microsoft-365/microsoft-365-apps/access/how-to-change-query-properties) |
| Automatic joins | One join per defined relationship, using the relationship's join type; inner otherwise | [Create a simple select query](https://support.microsoft.com/en-us/access/create-a-simple-select-query) |
| AutoJoin option | Without relationships, an inner join when two tables share a field of the same name and data type and one side is a primary key; never between queries; new queries only. Sources differ on "same name" vs "compatible type" | [Object designer settings](https://support.microsoft.com/en-us/access/customize-design-settings-for-objects-in-your-database) |
| Creating a join | Drag a field onto another source's field; creates an inner join. The table dragged from is the left table in Join Properties | [Video: outer joins](https://support.microsoft.com/en-us/access/training/video-create-queries-with-outer-joins) |
| No join line | Cross product (`FROM A, B`), with no warning | [Join tables and queries](https://support.microsoft.com/en-us/office/join-tables-and-queries-3f5838bd-24a0-4832-9bc1-07061a1478f6) |
| Join Properties | Double-click the line. 1: "Only include rows where the joined fields from both tables are equal." 2: "Include ALL records from 'Customers' and only those records from 'Orders' where the joined fields are equal." 3: the reverse | [Edit a relationship](https://support.microsoft.com/en-us/access/create-edit-or-delete-a-relationship) |
| Arrow | Points to the side that shows only matching rows | [Edit a relationship](https://support.microsoft.com/en-us/access/create-edit-or-delete-a-relationship) |
| Compound joins | Several lines between two tables; SQL `ON (a = b) AND (c = d)`. Extra grouping parentheses stop Design view opening it | [KB 207868](https://ftp.zx.net.nz/pub/archive/ftp.microsoft.com/MISC/KB/en-us/207/868.HTM) |
| Nesting | Outer joins may nest inside inner joins, not the reverse; SQL uses parentheses | [Perform joins using Access SQL](https://learn.microsoft.com/en-us/office/vba/access/concepts/structured-query-language/perform-joins-using-access-sql) |
| Ambiguous outer joins | Error when an outer join points toward an inner join, or two outer joins point toward each other: "The SQL statement could not be executed because it contains ambiguous outer joins. To force one of the joins to be performed first, create a separate query that performs the first join and then include that query in your SQL statement." | [Video: ambiguous outer join](https://support.microsoft.com/en-au/office/video-fix-the-ambiguous-outer-join-error-c36948dc-c481-48c6-809d-38427e9ffdd7) |
| Unequal joins | Not supported in Design view; SQL view only. Message: "Microsoft Access can't represent the join expression … in Design view." | [Join tables and queries](https://support.microsoft.com/en-us/office/join-tables-and-queries-3f5838bd-24a0-4832-9bc1-07061a1478f6) |
| Full outer joins | Not supported; a UNION of a LEFT and a RIGHT join instead | [Join tables and queries](https://support.microsoft.com/en-us/office/join-tables-and-queries-3f5838bd-24a0-4832-9bc1-07061a1478f6) |

## Design grid

- **Rows:** Field, Table, Total (only with Totals on), Sort, Show, Criteria, or, and further rows. Insert Rows and Delete Rows add or remove criteria rows; no maximum is documented.
- **Combining criteria:** AND across a row, OR between rows. A condition that applies to every alternative is repeated on every row. Access writes heavy parentheses: `WHERE (((T.ID)=1) AND ((T.Name)="John")) OR (((T.ID)=2));`
- **Show off:** the field is left out of SELECT but still used in WHERE and ORDER BY. Access 2003 help says hidden fields move to the rightmost columns when the query closes, and a hidden field with no sort or criteria is dropped (*unverified* for current versions).
- **Field row:** `Alias: expression` becomes `expression AS Alias`; without an alias Access names it `Expr1`.
- **Asterisk:** `Table.*` is equivalent to Output All Fields. Sorting, criteria or totals on it are refused (errors 2322, 2323, 2324); add the field separately with Show off.
- **Sort:** priority runs left to right across the grid; hidden columns can sort.
- **Limits:** 1,024 characters per cell, 99 ANDs in a WHERE or HAVING clause, about 64,000 characters of SQL, 255 output fields ([Access specifications](https://support.microsoft.com/en-us/office/access-specifications-0cf3c66f-9cf2-4e32-9568-98c1025bb47c)).

## Totals row

Turning Totals on sets every column to Group By. Criteria under a Group By column go into HAVING; to filter rows before grouping, the field is added again with Total set to Where.

| Total | SQL Access writes | Criteria go to | Notes |
| --- | --- | --- | --- |
| Group By | Column in SELECT and GROUP BY | HAVING | |
| Sum, Avg, Min, Max, Count, StDev, Var | `Sum(T.F) AS SumOfF` | HAVING, on the aggregate | Default name is function + `Of` + field |
| First, Last | `First(T.F) AS FirstOfF` | HAVING | Arbitrary: a row from the group in stored order; the query's sort does not control it (see below) |
| Expression | The expression as written, not grouped | HAVING (*unverified*) | Used for expressions that already contain aggregates; Access turns Sum on a calculated field into Expression when the query is reopened |
| Where | Field in WHERE only, not in SELECT or GROUP BY | WHERE | Show must be off: error 2320 otherwise |

```sql
SELECT States.Region, Count(States.Region) AS CountOfRegion
FROM States
WHERE (((States.AreaSqrMiles)>=50000))
GROUP BY States.Region
HAVING (((States.Region) Like "*south*"));
```

**Criteria rows that mix Where and grouped columns.** Access sends each cell to WHERE or HAVING by its column's Total, then ORs each clause across the rows on its own: rows *(W1, H1)* and *(W2, H2)* become `WHERE W1 OR W2 … HAVING H1 OR H2`, not `(W1 AND H1) OR (W2 AND H2)`. The row pairing is lost without a warning. Reopened in Design view, WHERE's and HAVING's disjuncts are laid out independently, each from the first criteria row. Not documented by Microsoft; shown by the SQL Access generated in [Microsoft Q&A 5230684](https://learn.microsoft.com/en-us/answers/questions/5230684/query-criteria-is-being-moved-combined-into-one-li). Microsoft's "Sum data" page names the three kinds of criteria (exclude records before totalling, ignore groups, hide totals after) but says nothing about several rows.

**First and Last.** "Return a field value from the first or last record in the result set returned by a query … Because records are usually returned in no particular order (unless the query includes an ORDER BY clause), the records returned by these functions will be arbitrary" ([First, Last functions](https://learn.microsoft.com/en-us/office/vba/access/concepts/miscellaneous/first-last-functions-microsoft-access-sql)). Nothing is documented per group. In a totals query the ORDER BY sorts the grouped result, not the rows inside a group, so it does not decide First or Last ([Codekabinett](https://codekabinett.com/rdumps.php?Lang=2&targetDoc=dfirst-dlast-sorted-result-set)); KB Q109380 says DFirst/DLast "ignore sort orders and indexes, even Primary Keys … This behavior is by design" ([mirror](https://techshelps.github.io/MSDN/KB/Source/access/q109380.htm)). Experts recommend Min/Max, or a TOP 1 subquery with an ORDER BY, instead. Access writes `First(MyTable.Buyer) AS FirstOfBuyer`.

Sources: [Sum data by using a query](https://support.microsoft.com/en-us/access/sum-data-by-using-a-query), [FunctionX lesson 45](https://www.functionx.com/access/Lesson45.htm), [Allen Browne, common query hurdles](http://allenbrowne.com/QueryPerfIssue.html), [FMS Access error list](https://www.fmsinc.com/microsoftaccess/errors/ErrorNumberAccess2007-2000.pdf).

## Criteria cells

- **Implied operand:** the column is the left operand; a bare value means equality. Forms include `<100`, `Not "Mexico"`, `Like "*Korea*"`, `Between 50 And 100` (inclusive, either order), `In("France","China")`, `Is Null`, `"" Or Is Null`.
- **Full expressions:** a cell may name other fields (`Len([CountryRegion]) > 10`), call functions (`Date()`, `DateAdd`, `Year`), use domain aggregates and subqueries (`>(SELECT AVG([UnitPrice]) FROM [Products])`).
- **Normalisation when the cell is left:** quotes added around text (`Paris` → `"Paris"`); `#` added around recognised dates; `*t*` becomes `Like "*t*"`; keywords capitalised (`and` → `And`); brackets added around field names; a leading `=` removed; `1` or `-1` becomes `True` and `0` becomes `False` on Yes/No fields.
- **Literals:** text in double quotes in the grid; dates in `#…#`, entered in local format in the grid but written `#m/d/yyyy#` in SQL; `True`, `False`, `Yes`, `No`; `""` for a zero-length string; `Is Null` for missing values.
- **Wildcards depend on the database's mode:** ANSI-89 (default) uses `* ? # [ ] !`; ANSI-92 ("SQL Server Compatible Syntax") uses `% _ [ ] ^`. A database uses one or the other, never both.
- **Case:** text comparisons are case-insensitive in the Access engine.
- **Parameters:** `[Enter start date:]` in a cell prompts when the query runs; types are declared in the Query Parameters dialog, written as a `PARAMETERS` clause. Any name Access cannot resolve becomes a parameter prompt.
- **Errors:** syntax errors such as "The expression you entered contains invalid syntax…"; "Data type mismatch in criteria expression" appears when the query runs. Some wrong criteria raise no error (`>3/31/13<7/1/13` returns every date).

Sources: [Examples of query criteria](https://support.microsoft.com/en-us/office/examples-of-query-criteria-3197228c-8684-4552-ac03-aba746fb29d8), [Query criteria part 2](https://support.microsoft.com/en-us/access/query-criteria-part-2-like-what), [Guide to expression syntax](https://support.microsoft.com/en-us/access/guide-to-expression-syntax), [Wildcard reference](https://support.microsoft.com/en-us/office/access-wildcard-character-reference-af00c501-7972-40ee-8889-e18abaad12d1), [Use parameters](https://support.microsoft.com/topic/c2806d3d-d500-45a8-8507-ec6af351b6ed), [KB 244693](https://ftp.zx.net.nz/pub/archive/ftp.microsoft.com/MISC/KB/en-us/244/693.HTM), [Special-character errors](https://learn.microsoft.com/en-us/office/troubleshoot/access/error-using-special-characters).

## Query properties (select queries)

| Property | SQL | Behaviour |
| --- | --- | --- |
| Unique Values | `SELECT DISTINCT` | Distinct over shown fields; results read-only |
| Unique Records | `SELECT DISTINCTROW` | Distinct over whole source rows; only with several tables; mutually exclusive with Unique Values |
| Top Values | `TOP n` or `TOP n PERCENT` | Rows tied with the last row are also returned (see below); also set by the Return box |
| Output All Fields | Equivalent to `*` | Grid only needs sort and criteria columns |
| Recordset Type, Record Locks, ODBC Timeout, Max Records, Filter, Order By | None | Run-time or datasheet behaviour |

**Top Values in detail.** What the documentation states, and what it leaves open:

- **Ties:** "If you specify that a specific number of records be returned, all records with values that match the value in the last record are also returned" (TopValues property). "The TOP predicate does not choose between equal values … if the twenty-fifth and twenty-sixth highest grade point averages are the same, the query will return 26 records" (TOP predicate).
- **No sort:** "If you don't include the ORDER BY clause, the query will return an arbitrary set of 25 records" (TOP predicate).
- **Totals queries:** the selection "is based on the *entire* output set, even if the query uses the GROUP BY clause"; there is no "top 5 within each group" (Jet Programmer's Guide).
- **The value:** "The value that follows TOP must be an unsigned Integer" (TOP predicate); the property is "an Integer value … or a number followed by a percent sign (%)". No range is stated.
- **Not documented:** how `n PERCENT` becomes a row count (rounded up, down or to nearest), whether ties apply to percentages, and whether ties are judged on every sort field or only the leftmost. Query Studio judges ties on every sort field (the whole ORDER BY), which is how "the value in the last record" reads when there are several sort fields.

Sources: [ALL, DISTINCT, DISTINCTROW, TOP predicates](https://learn.microsoft.com/en-us/office/vba/access/Concepts/Structured-Query-Language/all-distinct-distinctrow-top-predicates-microsoft-access-sql), [Jet Programmer's Guide: TOP N and TOP N PERCENT](https://techshelps.github.io/MSDN/DNJET/html/c4_body_21.htm) (mirror).

Sources: [UniqueValues](https://learn.microsoft.com/en-us/office/vba/access/concepts/miscellaneous/uniquevalues-property), [UniqueRecords](https://learn.microsoft.com/en-us/office/vba/access/concepts/miscellaneous/uniquerecords-property), [TopValues](https://learn.microsoft.com/office/vba/access/concepts/miscellaneous/topvalues-property), [OutputAllFields](https://learn.microsoft.com/en-us/office/vba/access/concepts/miscellaneous/outputallfields-property).

## Views and storage

- **Storage:** a saved query is its SQL text (`QueryDef.SQL`), to which Access may add spaces, semicolons and line breaks. The grid is rebuilt from it when the query reopens, and the query reopens in the view it was saved in.
- **Rewriting:** saving from Design view adds table prefixes, brackets and parentheses; OR criteria typed in one cell can come back split across columns.
- **SQL view only:** UNION, pass-through and data-definition queries, and unequal joins. Access 2003 rewrote derived tables as `[SELECT …]. AS T`.
- **Query types:** Select, Make Table, Append, Update, Delete, Crosstab; each changes the grid's rows (for example Update To replaces Sort and Show).
- **Keys:** F4 property sheet, Shift+F2 Zoom box, Ctrl+F2 Builder, F6 switches between the tables pane and the grid, Spacebar toggles Show.

Sources: [QueryDef.SQL](https://learn.microsoft.com/en-us/office/client-developer/access/desktop-database-reference/querydef-sql-property-dao), [SQL-specific queries](https://learn.microsoft.com/en-us/office/vba/access/concepts/miscellaneous/sql-specific-query), [Introduction to queries](https://support.microsoft.com/en-us/access/introduction-to-queries), [Keyboard shortcuts](https://support.microsoft.com/en-us/office/keyboard-shortcuts-for-access-70a673e4-4f7b-4300-b8e5-3320fa6606e2), [Q&A: Access modifying queries](https://learn.microsoft.com/en-ca/answers/questions/5193823/ms-access-2019-automatically-modifying-queries).
