import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Line-by-line Java PORT of the Kotlin pure-logic (CountdownEngine, Cadence, ExamCodec, ExamSelection, ExamRepo,
 * ModelBuilder, CfgCodec) plus every assertion from the Kotlin unit tests. It proves the LOGIC and the expected
 * numbers on a real JVM (JDK 21 java.time). It does NOT compile or run the Kotlin sources.
 */
public class VerifyP0 {
    static int pass = 0, fail = 0;
    static void check(String name, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) { pass++; }
        else { fail++; System.out.println("FAIL " + name + ": expected <" + expected + "> actual <" + actual + ">"); }
    }
    static void ok(String name, boolean cond) { check(name, true, cond); }

    // ---------------- CountdownEngine ----------------
    enum Phase { UPCOMING, TODAY, PAST }
    record Countdown(Phase phase, long calendarDays, Duration untilStart, Duration remainingToday) {
        long hoursUntilStart() { return untilStart.toHours(); }
    }
    static Countdown compute(LocalDate eventDate, ZonedDateTime now) {
        ZoneId zone = now.getZone();
        LocalDate today = now.toLocalDate();
        long days = ChronoUnit.DAYS.between(today, eventDate);
        Duration untilStart = Duration.between(now, eventDate.atStartOfDay(zone));
        Duration remainingToday = Duration.between(now, today.plusDays(1).atStartOfDay(zone));
        Phase phase = days > 0 ? Phase.UPCOMING : days == 0 ? Phase.TODAY : Phase.PAST;
        return new Countdown(phase, days, untilStart.isNegative() ? Duration.ZERO : untilStart, remainingToday);
    }
    static long weeks(long d) { return d <= 0 ? 0 : d / 7; }

    // ---------------- Cadence ----------------
    static final long SLACK = 20;
    static long alignedDelay(long nowMs, long period) { return period - Math.floorMod(nowMs, period) + SLACK; }
    static long nextMidnightMillis(ZonedDateTime now) {
        return now.toLocalDate().plusDays(1).atStartOfDay(now.getZone()).toInstant().toEpochMilli();
    }

    // ---------------- Exam model ----------------
    record Exam(String name, long epochDay, long createdDay) {}
    record Upcoming(Exam exam, long ringStartDay) {}
    record Decoded(List<Exam> exams, int skipped) {}
    static final String V2 = "v2:"; static final int MAX_NAME = 24; static final long MAX_EPOCH = 110_000L;
    static String kTrim(String s) { // Kotlin String.trim(): Char.isWhitespace
        int a = 0, b = s.length();
        while (a < b && (Character.isWhitespace(s.charAt(a)) || Character.isSpaceChar(s.charAt(a)))) a++;
        while (b > a && (Character.isWhitespace(s.charAt(b - 1)) || Character.isSpaceChar(s.charAt(b - 1)))) b--;
        return s.substring(a, b);
    }
    static String take(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }
    static String sanitizeName(String raw) {
        String t = take(kTrim(raw.replaceAll("[|;\\r\\n]", " ")), MAX_NAME);
        return t.isEmpty() ? "EVENT" : t;
    }
    static boolean isLegacy(String raw) { return raw != null && !raw.isBlank() && !raw.startsWith(V2); }
    static String encode(List<Exam> l) {
        StringBuilder sb = new StringBuilder(V2);
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(';');
            Exam e = l.get(i);
            sb.append(sanitizeName(e.name())).append('|').append(e.epochDay()).append('|').append(e.createdDay());
        }
        return sb.toString();
    }
    static Long toLongOrNull(String s) { try { return Long.parseLong(s); } catch (Exception e) { return null; } }
    static Decoded decode(String raw, long legacyStartDay) {
        if (raw == null || raw.isBlank()) return new Decoded(List.of(), 0);
        boolean v2 = raw.startsWith(V2);
        String body = v2 ? raw.substring(V2.length()) : raw;
        if (body.isBlank()) return new Decoded(List.of(), 0);
        int skipped = 0; List<Exam> out = new ArrayList<>();
        for (String part : body.split(";", -1)) {          // Kotlin split keeps empty trailing parts
            String[] a = part.split("\\|", -1);
            boolean sizeOk = v2 ? a.length == 3 : a.length == 2;
            Long epoch = a.length > 1 ? toLongOrNull(a[1]) : null;
            Long created = v2 ? (a.length > 2 ? toLongOrNull(a[2]) : null) : Long.valueOf(legacyStartDay);
            if (!sizeOk || epoch == null || created == null || epoch < 0 || epoch > MAX_EPOCH) { skipped++; continue; }
            out.add(new Exam(sanitizeName(a[0]), epoch, created));
        }
        return new Decoded(out, skipped);
    }
    static Upcoming next(List<Exam> exams, long today) {
        List<Exam> sorted = new ArrayList<>(exams);
        sorted.sort(Comparator.comparingLong(Exam::epochDay).thenComparing(Exam::name));
        int i = -1;
        for (int k = 0; k < sorted.size(); k++) if (sorted.get(k).epochDay() >= today) { i = k; break; }
        if (i < 0) return null;
        Exam e = sorted.get(i);
        Long prev = null;
        if (i - 1 >= 0 && sorted.get(i - 1).epochDay() < e.epochDay()) prev = sorted.get(i - 1).epochDay();
        return new Upcoming(e, Math.max(e.createdDay(), prev != null ? prev : e.createdDay()));
    }
    static float progress(long start, long event, long today) {
        if (today >= event) return 1f;
        long total = Math.max(event - start, 1L);
        long done = Math.min(Math.max(today - start, 0L), total);
        return (float) done / (float) total;
    }

    // In-memory store + repo
    static class MemoryStore {
        final Map<String, Object> m = new HashMap<>();
        MemoryStore() {} MemoryStore(Map<String, Object> init) { m.putAll(init); }
        Map<String, Object> all() { return new HashMap<>(m); }
        void write(Map<String, Object> v) { v.forEach((k, x) -> { if (x == null) m.remove(k); else m.put(k, x); }); }
    }
    static class Repo {
        final MemoryStore store; final java.util.function.LongSupplier today; final List<String> log;
        List<Exam> cache; int version = 0;
        Repo(MemoryStore s, long today, List<String> log) { store = s; this.today = () -> today; this.log = log; }
        Repo(MemoryStore s, long today) { this(s, today, new ArrayList<>()); }
        List<Exam> load() {
            if (cache != null) return cache;
            Map<String, Object> all = store.all();
            String raw = all.get("exams") instanceof String ? (String) all.get("exams") : null;
            long legacy = all.get("s") instanceof Long ? (Long) all.get("s") : today.getAsLong();
            Decoded d = decode(raw, legacy);
            if (d.skipped() > 0) log.add("Skipped " + d.skipped() + " unreadable countdown entries");
            if (isLegacy(raw) && !d.exams().isEmpty()) {
                store.write(Map.of("exams", encode(d.exams()))); log.add("Migrated " + d.exams().size() + " countdowns to v2 format");
            }
            cache = d.exams(); return cache;
        }
        void save(List<Exam> l) { List<Exam> c = new ArrayList<>(); for (Exam e : l) c.add(new Exam(sanitizeName(e.name()), e.epochDay(), e.createdDay())); cache = c; version++; store.write(Map.of("exams", encode(c))); }
        void add(String n, long ed) { List<Exam> l = new ArrayList<>(load()); l.add(new Exam(n, ed, today.getAsLong())); save(l); }
        void updateDate(Exam old, long nd) { List<Exam> l = new ArrayList<>(); for (Exam e : load()) l.add(e.equals(old) ? new Exam(e.name(), nd, e.createdDay()) : e); save(l); }
        void remove(Exam old) { List<Exam> l = new ArrayList<>(); for (Exam e : load()) if (!e.equals(old)) l.add(e); save(l); }
        Upcoming next(long t) { return VerifyP0.next(load(), t); }
    }

    // ---------------- ModelBuilder ----------------
    record Cfg(int unit, String label) { Cfg() { this(0, ""); } }
    record Model(boolean hasEvent, String name, Phase phase, long bigNumber, String label, String dateText,
                 String timeLeftText, String timeLeftNoSeconds, float progress, String urgency) {}
    static String pad2(long n) { return n < 10 ? "0" + n : Long.toString(n); }
    static Model build(List<Exam> exams, Cfg cfg, ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        Upcoming up = next(exams, today.toEpochDay());
        String custom = kTrim(cfg.label()).toUpperCase(Locale.ROOT);
        if (up == null) return new Model(false, "", Phase.PAST, -1, custom.isEmpty() ? "ADD A COUNTDOWN" : custom, "", "", "", 1f, "NONE");
        LocalDate date = LocalDate.ofEpochDay(up.exam().epochDay());
        Countdown cd = compute(date, now);
        long big = cfg.unit() == 1 ? weeks(cd.calendarDays()) : cfg.unit() == 2 ? cd.hoursUntilStart() : cd.calendarDays();
        String unitName = cfg.unit() == 1 ? "WEEKS" : cfg.unit() == 2 ? "HOURS" : "DAYS";
        String name = up.exam().name();
        String label = !custom.isEmpty() ? custom : (cd.phase() == Phase.TODAY && cfg.unit() == 0) ? name + " IS TODAY" : unitName + " TO " + name;
        long secs = Math.max(cd.remainingToday().getSeconds(), 0L);
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        String urg = cd.calendarDays() <= 10 ? "RED" : cd.calendarDays() <= 30 ? "AMBER" : "NONE";
        return new Model(true, name, cd.phase(), big, label,
            date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)).toUpperCase(Locale.ROOT),
            h + "H " + pad2(m) + "M " + pad2(s) + "S LEFT TODAY", h + "H " + pad2(m) + "M LEFT TODAY",
            progress(up.ringStartDay(), up.exam().epochDay(), today.toEpochDay()), urg);
    }

    // ---------------- CfgCodec (subset that has logic) ----------------
    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static String cleanText(String s) { StringBuilder b = new StringBuilder(); for (char c : s.toCharArray()) if (!Character.isISOControl(c)) b.append(c); return take(kTrim(b.toString()), 30); }
    static int opaque(int c) { return c | 0xFF000000; }

    // ---------------- helpers ----------------
    static ZonedDateTime at(String zone, String local) { return LocalDateTime.parse(local).atZone(ZoneId.of(zone)); }
    static Countdown cd(String zone, String nowLocal, String event) { return compute(LocalDate.parse(event), at(zone, nowLocal)); }
    static long day(int y, int m, int d) { return LocalDate.of(y, m, d).toEpochDay(); }
    static Exam ex(String name, String date, String created) { return new Exam(name, LocalDate.parse(date).toEpochDay(), LocalDate.parse(created).toEpochDay()); }
    static Exam ex(String name, String date) { return ex(name, date, "2026-11-01"); }

    public static void main(String[] args) {
        String IST = "Asia/Kolkata", NY = "America/New_York";
        // ===== CountdownEngineTest =====
        Countdown c = cd(IST, "2026-11-21T10:30:15", "2026-11-21");
        check("eventToday.phase", Phase.TODAY, c.phase()); check("eventToday.days", 0L, c.calendarDays());
        check("eventToday.until", 0L, c.untilStart().getSeconds()); check("eventToday.remaining", 48585L, c.remainingToday().getSeconds());
        c = cd(IST, "2026-11-21T10:30:15", "2026-11-22");
        check("tomorrow.phase", Phase.UPCOMING, c.phase()); check("tomorrow.days", 1L, c.calendarDays());
        check("tomorrow.until", 48585L, c.untilStart().getSeconds()); check("tomorrow.hours", 13L, c.hoursUntilStart());
        c = cd(IST, "2026-11-19T00:00:00", "2027-01-22");
        check("jee64.days", 64L, c.calendarDays()); check("jee64.hours", 1536L, c.hoursUntilStart());
        c = cd(IST, "2027-01-20T00:00:00", "2027-01-20");
        check("midnightEventDay.phase", Phase.TODAY, c.phase()); check("midnightEventDay.until", 0L, c.untilStart().getSeconds()); check("midnightEventDay.rem", 86400L, c.remainingToday().getSeconds());
        c = cd(IST, "2027-01-19T23:59:59", "2027-01-20");
        check("1sBefore.phase", Phase.UPCOMING, c.phase()); check("1sBefore.days", 1L, c.calendarDays()); check("1sBefore.until", 1L, c.untilStart().getSeconds()); check("1sBefore.hours", 0L, c.hoursUntilStart()); check("1sBefore.rem", 1L, c.remainingToday().getSeconds());
        c = cd(IST, "2027-01-21T09:00:00", "2027-01-20");
        check("past.phase", Phase.PAST, c.phase()); check("past.days", -1L, c.calendarDays()); check("past.until", 0L, c.untilStart().getSeconds()); check("past.rem", 54000L, c.remainingToday().getSeconds());
        check("leap1", 2L, cd("UTC", "2028-02-28T12:00:00", "2028-03-01").calendarDays());
        check("leap2", 1L, cd("UTC", "2028-02-28T12:00:00", "2028-02-29").calendarDays());
        check("leap3", 1L, cd("UTC", "2027-02-28T12:00:00", "2027-03-01").calendarDays());
        c = cd("UTC", "2026-12-31T23:00:00", "2027-01-01"); check("year.days", 1L, c.calendarDays()); check("year.until", 3600L, c.untilStart().getSeconds());
        c = cd("UTC", "2026-11-30T18:00:00", "2026-12-01"); check("month.days", 1L, c.calendarDays()); check("month.until", 21600L, c.untilStart().getSeconds());
        c = cd(NY, "2027-03-13T12:00:00", "2027-03-15"); check("springFwd.days", 2L, c.calendarDays()); check("springFwd.until", 126000L, c.untilStart().getSeconds()); check("springFwd.hours", 35L, c.hoursUntilStart());
        c = cd(NY, "2027-11-06T12:00:00", "2027-11-08"); check("fallBack.days", 2L, c.calendarDays()); check("fallBack.until", 133200L, c.untilStart().getSeconds()); check("fallBack.hours", 37L, c.hoursUntilStart());
        check("23hDay", 82800L, cd(NY, "2027-03-14T00:00:00", "2027-03-15").remainingToday().getSeconds());
        check("25hDay", 90000L, cd(NY, "2027-11-07T00:00:00", "2027-11-08").remainingToday().getSeconds());
        Instant inst = Instant.parse("2026-11-20T20:00:00Z"); LocalDate ev = LocalDate.parse("2026-11-21");
        Countdown k = compute(ev, inst.atZone(ZoneId.of(IST))), la = compute(ev, inst.atZone(ZoneId.of("America/Los_Angeles"))), au = compute(ev, inst.atZone(ZoneId.of("Pacific/Auckland")));
        check("zones.kolkata", Phase.TODAY, k.phase()); check("zones.la.phase", Phase.UPCOMING, la.phase()); check("zones.la.days", 1L, la.calendarDays()); check("zones.la.until", 43200L, la.untilStart().getSeconds()); check("zones.auckland", Phase.TODAY, au.phase());
        check("clockChange1", 11L, cd("UTC", "2026-11-20T12:00:00", "2026-12-01").calendarDays()); check("clockChange2", 21L, cd("UTC", "2026-11-10T12:00:00", "2026-12-01").calendarDays());
        check("weeks.-3", 0L, weeks(-3)); check("weeks.0", 0L, weeks(0)); check("weeks.6", 0L, weeks(6)); check("weeks.7", 1L, weeks(7)); check("weeks.64", 9L, weeks(64));
        c = cd("UTC", "2028-02-28T12:00:00", "2028-03-01");
        check("distinct.days", 2L, c.calendarDays()); check("distinct.hours", 36L, c.hoursUntilStart()); check("distinct.rem", 12L * 3600L, c.remainingToday().getSeconds()); ok("distinct.neq", c.untilStart().getSeconds() != c.calendarDays() * 86400L);

        // ===== ExamModelTest =====
        List<Exam> list = List.of(new Exam("JEE MAIN", 20_000, 19_900), new Exam("MY BIRTHDAY", 20_100, 19_950));
        Decoded d = decode(encode(list), 0L); check("codec.roundtrip", list, d.exams()); check("codec.skipped0", 0, d.skipped());
        check("name.a", "A B C", sanitizeName("A|B;C")); check("name.empty", "EVENT", sanitizeName("  |;  ")); check("name.cap", MAX_NAME, sanitizeName("X".repeat(100)).length());
        d = decode("JEE MAIN|20000;NEET UG|20100", 19_000L);
        check("legacy.size", 2, d.exams().size()); check("legacy.first", new Exam("JEE MAIN", 20_000, 19_000), d.exams().get(0)); check("legacy.created", 19_000L, d.exams().get(1).createdDay());
        d = decode("v2:A|x|1;B|20000|5;C|20000;D|-5|1;E|999999999|1", 0L);
        check("corrupt.list", List.of(new Exam("B", 20_000, 5)), d.exams()); check("corrupt.skipped", 4, d.skipped());
        for (String raw : new String[]{null, "", "   ", "v2:"}) { d = decode(raw, 0L); ok("empty.list[" + raw + "]", d.exams().isEmpty()); check("empty.skipped[" + raw + "]", 0, d.skipped()); }
        Upcoming up = next(List.of(new Exam("A", 100, 10)), 100); ok("today.counts", up != null && up.exam().name().equals("A"));
        ok("none.1", next(List.of(new Exam("A", 100, 10)), 101) == null); ok("none.2", next(List.of(), 0) == null);
        Exam a = new Exam("A", 100, 10), b = new Exam("B", 200, 20);
        check("ring.first", 10L, next(List.of(a, b), 50).ringStartDay()); check("ring.second", 100L, next(List.of(a, b), 150).ringStartDay());
        check("ring.createdAfterPrev", 150L, next(List.of(a, new Exam("C", 200, 150)), 160).ringStartDay());
        Exam x = new Exam("X", 200, 20), y = new Exam("Y", 200, 30);
        check("ring.sameDay", 20L, next(List.of(y, x), 100).ringStartDay());
        check("progress.half", 0.5f, progress(100, 200, 150)); check("progress.before", 0f, progress(100, 200, 90)); check("progress.onEvent", 1f, progress(100, 200, 200)); check("progress.after", 1f, progress(100, 200, 250)); check("progress.degenerate", 0f, progress(200, 200, 150));
        MemoryStore st = new MemoryStore(); Repo r1 = new Repo(st, 19_000L); r1.add("JEE MAIN", 20_000); r1.add("NEET UG", 20_100);
        check("restart", List.of(new Exam("JEE MAIN", 20_000, 19_000), new Exam("NEET UG", 20_100, 19_000)), new Repo(st, 19_500L).load());
        st = new MemoryStore(); Repo r = new Repo(st, 19_000L); r.add("A", 20_000); Exam ea = r.load().get(0); r.updateDate(ea, 20_050);
        check("edit.date", 20_050L, new Repo(st, 0L).load().get(0).epochDay()); check("edit.created", 19_000L, new Repo(st, 0L).load().get(0).createdDay());
        r.remove(r.load().get(0)); ok("delete.persist", new Repo(st, 0L).load().isEmpty());
        Repo rv = new Repo(new MemoryStore(), 1L); int v0 = rv.version; rv.add("A", 20_000); ok("version.bumps", rv.version > v0);
        st = new MemoryStore(Map.of("exams", "JEE MAIN|20000", "s", 19_000L)); List<String> logs = new ArrayList<>();
        check("upgrade.loaded", List.of(new Exam("JEE MAIN", 20_000, 19_000)), new Repo(st, 99_999L, logs).load());
        ok("upgrade.v2", ((String) st.all().get("exams")).startsWith("v2:")); ok("upgrade.logged", logs.stream().anyMatch(s -> s.contains("Migrated")));
        st = new MemoryStore(Map.of("exams", "v2:garbage;B|20000|5")); logs = new ArrayList<>();
        check("corruptRepo.loaded", List.of(new Exam("B", 20_000, 5)), new Repo(st, 1L, logs).load()); final List<String> L2 = logs; ok("corruptRepo.logged", L2.stream().anyMatch(s -> s.contains("Skipped 1")));
        ok("wrongType.empty", new Repo(new MemoryStore(Map.of("exams", 42)), 1L).load().isEmpty());
        Repo rn = new Repo(new MemoryStore(), day(2026, 11, 19)); rn.add("JEE MAIN", day(2027, 1, 22));
        check("repoNext.ring", day(2026, 11, 19), rn.next(day(2026, 12, 21)).ringStartDay());

        // ===== ModelBuilderTest =====
        ZoneId ist = ZoneId.of(IST); Exam jee = ex("JEE MAIN", "2027-01-22", "2026-11-19");
        Model m = build(List.of(), new Cfg(), at(IST, "2026-11-21T10:30:15")); ok("model.noEvent", !m.hasEvent()); check("model.noEvent.num", -1L, m.bigNumber()); check("model.noEvent.label", "ADD A COUNTDOWN", m.label());
        check("model.customLabelNoEvent", "MY GOAL", build(List.of(), new Cfg(0, "  my goal "), at(IST, "2026-11-21T10:30:15")).label());
        m = build(List.of(jee), new Cfg(), at(IST, "2026-11-19T00:00:00"));
        ok("model.days.has", m.hasEvent()); check("model.days.num", 64L, m.bigNumber()); check("model.days.label", "DAYS TO JEE MAIN", m.label()); check("model.days.date", "22 JAN 2027", m.dateText()); check("model.days.urg", "NONE", m.urgency());
        ZonedDateTime now = at(IST, "2026-11-19T00:00:00");
        m = build(List.of(jee), new Cfg(1, ""), now); check("model.weeks.num", 9L, m.bigNumber()); check("model.weeks.label", "WEEKS TO JEE MAIN", m.label());
        m = build(List.of(jee), new Cfg(2, ""), now); check("model.hours.num", 1536L, m.bigNumber()); check("model.hours.label", "HOURS TO JEE MAIN", m.label());
        m = build(List.of(jee), new Cfg(), at(IST, "2027-01-22T08:00:00")); ok("model.today.has", m.hasEvent()); check("model.today.phase", Phase.TODAY, m.phase()); check("model.today.num", 0L, m.bigNumber()); check("model.today.label", "JEE MAIN IS TODAY", m.label());
        now = at(IST, "2026-11-21T10:30:00"); LocalDate today = LocalDate.of(2026, 11, 21);
        long[] ds = {31, 30, 11, 10, 0}; String[] exp = {"NONE", "AMBER", "AMBER", "RED", "RED"};
        for (int i = 0; i < ds.length; i++) check("urgency days=" + ds[i], exp[i], build(List.of(ex("X", today.plusDays(ds[i]).toString())), new Cfg(), now).urgency());
        Exam past = ex("OLD", "2026-11-01"), later = ex("LATER", "2027-05-23"), sooner = ex("SOONER", "2026-12-25");
        check("model.nextUpcoming", "SOONER", build(List.of(later, past, sooner), new Cfg(), now).name()); ok("model.allPast", !build(List.of(past), new Cfg(), now).hasEvent());
        m = build(List.of(jee), new Cfg(), at(IST, "2026-11-21T10:30:15")); check("model.timeLeft", "13H 29M 45S LEFT TODAY", m.timeLeftText()); check("model.timeLeftNoSec", "13H 29M LEFT TODAY", m.timeLeftNoSeconds());
        Exam far = ex("YEAR END", "2027-12-31", "2027-01-01");
        check("model.dst23", "23H 00M 00S LEFT TODAY", build(List.of(far), new Cfg(), at(NY, "2027-03-14T00:00:00")).timeLeftText());
        check("model.dst25", "25H 00M 00S LEFT TODAY", build(List.of(far), new Cfg(), at(NY, "2027-11-07T00:00:00")).timeLeftText());
        check("model.progressHalf", 0.5f, build(List.of(jee), new Cfg(), at(IST, "2026-12-21T09:00:00")).progress());

        // ===== CfgCodec behaviours that have logic =====
        check("cfg.theme.clamp", 6, clamp(99, 0, 6)); check("cfg.bgMode.clamp", 0, clamp(-4, 0, 2)); check("cfg.dim.clamp", 100, clamp(500, 0, 100)); check("cfg.font.clamp", 4, clamp(42, 0, 4)); check("cfg.unit.clamp", 2, clamp(7, 0, 2));
        check("cfg.alpha.solid", 0xFF14211A, opaque(0x0014211A)); check("cfg.alpha.accent", 0xFFA9C98A, opaque(0x00A9C98A));
        String lab = cleanText("  A\u0000B\n" + "x".repeat(100)); check("cfg.text.len", 30, lab.length()); check("cfg.text.value", "AB" + "x".repeat(28), lab); check("cfg.text.goal", "", cleanText("\t"));

        // ===== NEW: Cadence =====
        check("cad.onBoundary", 1020L, alignedDelay(1_000_000L, 1000)); check("cad.mid", 770L, alignedDelay(1_000_250L, 1000));
        check("cad.justBefore", 21L, alignedDelay(30_000L * 5 + 29_999, 30_000)); check("cad.negative", 21L, alignedDelay(-1L, 30_000));
        boolean allOk = true;
        for (long t = -70_000; t <= 130_000; t += 37) for (long p : new long[]{1000, 30_000}) {
            long dl = alignedDelay(t, p);
            if (dl < 1 + SLACK || dl > p + SLACK || Math.floorMod(t + dl, p) != SLACK) { allOk = false; System.out.println("cad sweep bad t=" + t + " p=" + p + " dl=" + dl); }
        }
        ok("cad.sweep", allOk);
        // minute boundaries are hit by the 30 s grid (so midnight is never more than ~20 ms late):
        long midnightIst = nextMidnightMillis(at(IST, "2026-11-21T10:30:15"));
        check("mid.ist.epoch", Instant.parse("2026-11-21T18:30:00Z").toEpochMilli(), midnightIst);
        ok("mid.onGrid30s", Math.floorMod(midnightIst, 30_000L) == 0);
        check("mid.ny.normal", Instant.parse("2027-03-14T05:00:00Z").toEpochMilli(), nextMidnightMillis(at(NY, "2027-03-13T12:00:00")));
        long springMid = nextMidnightMillis(at(NY, "2027-03-14T00:00:00")); check("mid.ny.springFwd", Instant.parse("2027-03-15T04:00:00Z").toEpochMilli(), springMid);
        check("mid.ny.springFwd.len", 23L * 3600_000L, springMid - at(NY, "2027-03-14T00:00:00").toInstant().toEpochMilli());
        long fallMid = nextMidnightMillis(at(NY, "2027-11-07T00:00:00")); check("mid.ny.fallBack", Instant.parse("2027-11-08T05:00:00Z").toEpochMilli(), fallMid);
        check("mid.ny.fallBack.len", 25L * 3600_000L, fallMid - at(NY, "2027-11-07T00:00:00").toInstant().toEpochMilli());
        long havana = nextMidnightMillis(at("America/Havana", "2027-03-13T12:00:00"));
        System.out.println("INFO havana next-midnight (DST gap at 00:00) = " + Instant.ofEpochMilli(havana) + " ; local = " + Instant.ofEpochMilli(havana).atZone(ZoneId.of("America/Havana")));
        check("mid.havana.gap", Instant.parse("2027-03-14T05:00:00Z").toEpochMilli(), havana);
        check("mid.havana.len", 12L * 3600_000L, havana - at("America/Havana", "2027-03-13T12:00:00").toInstant().toEpochMilli());
        check("mid.kathmandu", Instant.parse("2026-11-21T18:15:00Z").toEpochMilli(), nextMidnightMillis(at("Asia/Kathmandu", "2026-11-21T10:30:15"))); // +05:45 zone
        ok("mid.alwaysFuture", nextMidnightMillis(at("UTC", "2026-12-31T23:59:59")) > at("UTC", "2026-12-31T23:59:59").toInstant().toEpochMilli());
        check("mid.yearEnd", Instant.parse("2027-01-01T00:00:00Z").toEpochMilli(), nextMidnightMillis(at("UTC", "2026-12-31T23:59:59")));

        System.out.println("\nRESULT: " + pass + " checks passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
