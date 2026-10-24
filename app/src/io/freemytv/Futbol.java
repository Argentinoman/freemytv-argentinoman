package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Football in place of the crypto quotes: ESPN's public scoreboard (no key; unofficial, so every field is read
 * defensively). Two lists: the highlights for the row under the clock (Racing first, then the national team, then
 * live games, the cups and the league) and every game of the coming week for the drawer card.
 * Runs on HomeData's background thread only; nothing here touches the UI.
 */
final class Futbol {
    private Futbol() {}

    /** The favourite club and national team (ESPN ids), from Home Settings; Racing and Argentina by default. */
    static volatile String CLUB = "15", NATION = "202";
    private static final String BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer/";
    /** Scoreboards to scan, most important first. */
    // In order of weight (the first goes first at the same moment): the World Cup, Copa América, CONMEBOL's World Cup
    // qualifiers, then the club cups, then Argentina's cups (every professional one) and league.
    private static final String[] LEAGUES = {"fifa.world", "conmebol.america", "fifa.worldq.conmebol",
            "conmebol.libertadores", "conmebol.sudamericana", "conmebol.recopa", "uefa.champions", "uefa.europa",
            "arg.supercopa.internacional", "arg.trofeo_de_la_campeones", "arg.supercopa", "arg.copa_de_la_superliga",
            "arg.copa", "arg.1"};
    private static final long DAY = 24 * 3600_000L, HOUR = 3600_000L;
    /** ESPN refuses date ranges (HTTP 400), so the week is read day by day: 2 days back, 8 ahead, every 3 hours. */
    private static final int DAYS_BACK = 2, DAYS_AHEAD = 8;
    private static final long WEEK_EVERY = 3 * HOUR;
    static final int MAX_HIGHLIGHTS = 8;

    static final class Team {
        String id = "", name = "", shortName = "", abbr = "", logo = "", score = "";
        String pens = "";   // the penalty shootout, when there was one
        Bitmap crest;
    }

    static final class Match {
        String id = "", league = "";
        long start;
        String state = "pre"; // pre, in, post
        String clock = "", detail = "";
        final Team home = new Team(), away = new Team();
        int weight;
        String stage = "", leg = "";   // ESPN's season slug ("semifinals", "torneo-clausura") and "2nd Leg"
        int fecha;                     // Liga matchday, worked out from Racing's games (ESPN doesn't give it)
        String round = "";             // "Fecha 15 · Liga Profesional", "Semifinal · vuelta · Libertadores"

        boolean live() { return "in".equals(state); }
        /**
         * Past its kickoff but still "pre" in what we have (ESPN flips it late, or our copy is a few minutes old):
         * almost surely being played, so it isn't dropped from the row while the score catches up.
         */
        boolean kickedOff(long now) {
            return "pre".equals(state) && !detail.contains("TBD") && start <= now && now - start < 150 * 60_000L;
        }
        boolean done() { return "post".equals(state); }
        boolean has(String team) { return team.equals(home.id) || team.equals(away.id); }
        boolean club() { return has(CLUB); }
    }

    static final class Result {
        final List<Match> highlights, upcoming;
        /** The home screen's game(s): every big game being played now, else Racing's of the day or its next. */
        final List<Match> featured;

        Result(List<Match> highlights, List<Match> upcoming, List<Match> featured) {
            this.highlights = highlights;
            this.upcoming = upcoming;
            this.featured = featured;
        }
    }

    private static Map<String, Match> week = new HashMap<>();
    private static long weekAt;
    private static final Map<String, Bitmap> crests = new ConcurrentHashMap<>();

    static boolean anyLive(List<Match> list) {
        long now = System.currentTimeMillis();
        for (Match m : list) if (m.live() || m.kickedOff(now)) return true;   // every minute: the score comes soon
        return false;
    }

    /** True until the first full week has been read: the next fetch should be a quick one first. */
    static boolean weekMissing() {
        return week.isEmpty();
    }

    /** quick: skip the week scan (the first time, so Racing's game shows in seconds rather than in half a minute). */
    static Result fetch(File crestDir, boolean quick) throws Exception {
        long now = System.currentTimeMillis();
        boolean any = false;
        if (!quick && (now - weekAt > WEEK_EVERY || now < weekAt || week.isEmpty())) {
            Map<String, Match> w = new HashMap<>();
            SimpleDateFormat ymd = new SimpleDateFormat("yyyyMMdd", Locale.US);
            for (int i = 0; i < LEAGUES.length; i++) {
                for (int d = -DAYS_BACK; d <= DAYS_AHEAD; d++) {
                    if (scoreboard(LEAGUES[i], "?dates=" + ymd.format(new Date(now + d * DAY)), LEAGUES.length - i, w)) any = true;
                }
            }
            if (any) {
                week = w;
                weekAt = now;
            }
        }
        // Today's scoreboards: the live clock and score, every few minutes.
        Map<String, Match> all = new LinkedHashMap<>(week);
        for (int i = 0; i < LEAGUES.length; i++) {
            if (scoreboard(LEAGUES[i], "", LEAGUES.length - i, all)) any = true;
        }
        List<Match> racing = new ArrayList<>(), arg = new ArrayList<>();
        if (team(CLUB, racing, all) | team(NATION, arg, all)) any = true;
        if (!any) throw new Exception("no answer from ESPN");

        numberFechas(racing, all.values());
        for (Match m : all.values()) m.round = round(m);
        for (Match m : racing) m.round = round(m);
        for (Match m : arg) m.round = round(m);
        List<Match> hl = highlights(racing, arg, all, now);
        // Nothing around now: the home screen and the screensaver show no game (no favourite team filling in).
        Result r = new Result(hl, upcoming(racing, arg, all, now), aroundNow(all.values(), now));
        loadCrests(crestDir, r);
        return r;
    }

    /** Racing's live, next and last game; the same for the national team; then live games, soon, recent. */
    private static List<Match> highlights(List<Match> racing, List<Match> arg, Map<String, Match> all, long now) {
        List<Match> out = new ArrayList<>();
        pickTeam(racing, out, now, 21);
        pickTeam(arg, out, now, 30);
        List<Match> live = new ArrayList<>(), soon = new ArrayList<>(), recent = new ArrayList<>();
        for (Match m : all.values()) {
            if (out.contains(m) || m.club() || m.has(NATION)) continue;
            if (m.live()) live.add(m);
            else if (!m.done() && m.start - now < 3 * DAY && m.start > now - 3 * HOUR) soon.add(m);
            else if (m.done() && now - m.start < 2 * DAY) recent.add(m);
        }
        Collections.sort(live, (a, b) -> b.weight - a.weight);
        // Same day: the cups first; otherwise in order of kickoff.
        Collections.sort(soon, (a, b) -> a.start / DAY != b.start / DAY ? Long.compare(a.start, b.start)
                : a.weight != b.weight ? b.weight - a.weight : Long.compare(a.start, b.start));
        Collections.sort(recent, (a, b) -> Long.compare(b.start, a.start));
        out.addAll(live);
        out.addAll(soon);
        out.addAll(recent);
        return out.size() > MAX_HIGHLIGHTS ? new ArrayList<>(out.subList(0, MAX_HIGHLIGHTS)) : out;
    }

    /** Every game still to play this week: live ones first, then Racing's next, then by kickoff. */
    private static List<Match> upcoming(List<Match> racing, List<Match> arg, Map<String, Match> all, long now) {
        Map<String, Match> pool = new LinkedHashMap<>();
        for (Match m : all.values()) {
            if (m.live() || today(m, now) || (!m.done() && m.start > now - 2 * HOUR && m.start < now + DAYS_AHEAD * DAY)) pool.put(m.id, m);
        }
        // The teams' next games count even beyond the week (the national team plays every month or two).
        for (List<Match> l : java.util.Arrays.asList(racing, arg)) {
            Match next = null;
            for (Match m : l) if (!m.done() && !m.live() && m.start > now - 2 * HOUR && (next == null || m.start < next.start)) next = m;
            if (next != null) pool.put(next.id, next);
        }
        List<Match> live = new ArrayList<>(), day = new ArrayList<>(), rest = new ArrayList<>();
        for (Match m : pool.values()) (m.live() ? live : today(m, now) ? day : rest).add(m);
        Collections.sort(live, (a, b) -> a.club() != b.club() ? (a.club() ? -1 : 1) : b.weight - a.weight);
        // By kickoff, so each new game takes its place as it comes closer; same time: the cups first.
        java.util.Comparator<Match> byKickoff = (a, b) -> a.start != b.start ? Long.compare(a.start, b.start) : b.weight - a.weight;
        Collections.sort(day, byKickoff);
        Collections.sort(rest, byKickoff);
        List<Match> out = new ArrayList<>(live);
        out.addAll(day);    // today's games, played ones with their result, before anything later
        out.addAll(rest);
        return out;
    }

    private static void pickTeam(List<Match> list, List<Match> out, long now, int horizonDays) {
        Match live = null, next = null, last = null;
        for (Match m : list) {
            if (m.live()) live = m;
            else if (!m.done() && m.start > now - 3 * HOUR) { if (next == null || m.start < next.start) next = m; }
            else if (m.done()) { if (last == null || m.start > last.start) last = m; }
        }
        if (live != null) out.add(live);
        else if (next != null && next.start - now < horizonDays * DAY) out.add(next);
        if (last != null && now - last.start < 12 * DAY) out.add(last);
    }

    /** Reads one scoreboard into `into`; a game already there is replaced (the newer copy has the live score). */
    private static boolean scoreboard(String league, String query, int weight, Map<String, Match> into) {
        JSONObject d = json(BASE + league + "/scoreboard" + query);
        if (d == null) return false;
        JSONArray lg = d.optJSONArray("leagues");
        String lname = lg != null && lg.length() > 0 ? lg.optJSONObject(0).optString("name") : "";
        JSONArray ev = d.optJSONArray("events");
        for (int j = 0; ev != null && j < ev.length(); j++) {
            Match m = parse(ev.optJSONObject(j), lname);
            if (m == null) continue;
            m.weight = weight;
            into.put(m.id, m);
        }
        return true;
    }

    /** A team's schedule (all competitions, past and coming); games the scoreboards have keep the scoreboard's copy. */
    private static boolean team(String id, List<Match> into, Map<String, Match> all) {
        boolean any = false;
        for (String q : new String[]{"", "?fixture=true"}) {
            JSONObject d = json(BASE + "all/teams/" + id + "/schedule" + q);
            if (d == null) continue;
            any = true;
            JSONArray ev = d.optJSONArray("events");
            for (int i = 0; ev != null && i < ev.length(); i++) {
                JSONObject e = ev.optJSONObject(i);
                JSONObject lg = e == null ? null : e.optJSONObject("league");
                Match m = parse(e, lg == null ? "" : lg.optString("name", lg.optString("abbreviation")));
                if (m == null) continue;
                Match known = all.get(m.id);
                if (known != null) m = known;
                else if (m.start > System.currentTimeMillis() - 3 * HOUR) all.put(m.id, m);
                into.add(m);
            }
        }
        return any;
    }

    private static Match parse(JSONObject e, String leagueName) {
        if (e == null) return null;
        JSONArray comps = e.optJSONArray("competitions");
        if (comps == null || comps.length() == 0) return null;
        JSONObject c = comps.optJSONObject(0);
        Match m = new Match();
        m.id = e.optString("id");
        m.start = iso(e.optString("date"));
        m.league = league(leagueName);
        JSONObject st = c.optJSONObject("status");
        if (st != null) {
            JSONObject t = st.optJSONObject("type");
            if (t != null) {
                m.state = t.optString("state", "pre");
                m.detail = t.optString("shortDetail", "");
            }
            m.clock = st.optString("displayClock", "");
        }
        JSONObject season = e.optJSONObject("season");
        if (season != null) m.stage = season.optString("slug", "");
        JSONObject leg = c.optJSONObject("leg");
        if (leg != null) m.leg = leg.optString("displayValue", "");
        JSONArray cs = c.optJSONArray("competitors");
        if (cs == null || cs.length() < 2) return null;
        for (int i = 0; i < cs.length(); i++) {
            JSONObject x = cs.optJSONObject(i);
            Team t = "away".equals(x.optString("homeAway")) ? m.away : m.home;
            Object sc = x.opt("score");
            if (sc instanceof JSONObject) t.score = ((JSONObject) sc).optString("displayValue", "");
            else if (sc != null && sc != JSONObject.NULL) t.score = String.valueOf(sc);
            Object so = x.opt("shootoutScore");
            if (so != null && so != JSONObject.NULL) {
                String p = so instanceof JSONObject ? ((JSONObject) so).optString("displayValue", "") : String.valueOf(so);
                if (p.endsWith(".0")) p = p.substring(0, p.length() - 2);
                t.pens = p;
            }
            JSONObject tm = x.optJSONObject("team");
            if (tm == null) continue;
            t.id = tm.optString("id");
            t.name = tm.optString("displayName");
            t.shortName = tm.optString("shortDisplayName", t.name);
            t.abbr = tm.optString("abbreviation");
            t.logo = tm.optString("logo", "");
            JSONArray logos = tm.optJSONArray("logos");
            if (t.logo.isEmpty() && logos != null && logos.length() > 0) t.logo = logos.optJSONObject(0).optString("href", "");
        }
        return m;
    }

    /** The competitions whose games go under the clock. */
    private static final java.util.Set<String> BIG = new java.util.HashSet<>(java.util.Arrays.asList("Liga", "Libertadores",
            "Sudamericana", "Recopa", "Champions", "Europa League", "Eliminatorias", "Copa América", "Mundial",
            "Copa Argentina", "Copa de la Liga", "Trofeo de Campeones", "Supercopa Argentina", "Supercopa Internacional"));

    private static boolean big(Match m) {
        return m.has(NATION) || BIG.contains(m.league);
    }

    /**
     * The big games (the league, the cups, the national team) worth the home screen right now: the ones being played,
     * then the results of the last hour, then the ones kicking off within three hours. No team goes first.
     */
    private static List<Match> aroundNow(java.util.Collection<Match> all, long now) {
        List<Match> live = new ArrayList<>(), soon = new ArrayList<>(), ended = new ArrayList<>();
        for (Match m : all) {
            if (!big(m)) continue;
            if (m.live() || m.kickedOff(now)) live.add(m);
            else if (!m.done() && m.start > now && m.start - now <= 3 * HOUR) soon.add(m);
            else if (m.done() && now - endOf(m) <= HOUR) ended.add(m);
        }
        // Same moment: the cups (higher weight) first.
        Collections.sort(live, (a, b) -> b.weight - a.weight);
        Collections.sort(ended, (a, b) -> Long.compare(endOf(b), endOf(a)));
        Collections.sort(soon, (a, b) -> a.start != b.start ? Long.compare(a.start, b.start) : b.weight - a.weight);
        List<Match> out = new ArrayList<>(live);
        out.addAll(ended);
        out.addAll(soon);
        return out;
    }

    /** About when a game ended: ESPN gives the kickoff only; two hours covers half-time and stoppage time. */
    private static long endOf(Match m) {
        return m.start + 2 * HOUR;
    }

    /** "2 – 1", or "1 – 1 (4-3 pen.)" after a shootout. */
    static String score(Match m) {
        String s = m.home.score + " – " + m.away.score;
        if (!m.home.pens.isEmpty() && !m.away.pens.isEmpty()) s += " (" + m.home.pens + "-" + m.away.pens + " pen.)";
        return s;
    }

    private static boolean today(Match m, long now) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        a.setTimeInMillis(now);
        b.setTimeInMillis(m.start);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * Liga matchdays. ESPN gives no number, so Racing's league games since the mid-year break are counted (Racing
     * plays every matchday), and every league game within three days of one of them gets that matchday's number.
     */
    private static void numberFechas(List<Match> racing, java.util.Collection<Match> all) {
        List<Match> liga = new ArrayList<>();
        for (Match m : racing) if (m.league.equals("Liga")) liga.add(m);
        Collections.sort(liga, (a, b) -> Long.compare(a.start, b.start));
        int from = 0;
        for (int i = 1; i < liga.size(); i++) if (liga.get(i).start - liga.get(i - 1).start > 21 * DAY) from = i;
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Match> season = new ArrayList<>();
        for (int i = from; i < liga.size(); i++) if (seen.add(liga.get(i).id)) season.add(liga.get(i));
        for (int i = 0; i < season.size(); i++) season.get(i).fecha = i + 1;
        for (Match m : all) {
            if (!m.league.equals("Liga") || m.fecha > 0) continue;
            for (Match r : season) if (Math.abs(r.start - m.start) < 3 * DAY + 12 * HOUR) { m.fecha = r.fecha; break; }
        }
    }

    /** "Fecha 15 · Liga Profesional", "Semifinal · vuelta · Libertadores", "Amistoso · Selección". */
    static String round(Match m) {
        if (m.league.equals("Liga")) return m.fecha > 0 ? "Fecha " + m.fecha + " · Liga Profesional" : "Liga Profesional";
        if (m.has(NATION)) return m.league + " · Selección";
        String stage = stage(m.stage);
        String leg = m.leg.startsWith("1") ? "ida" : m.leg.startsWith("2") ? "vuelta" : "";
        StringBuilder s = new StringBuilder();
        if (!stage.isEmpty()) s.append(stage).append(" · ");
        if (!leg.isEmpty()) s.append(leg).append(" · ");
        return s.append(m.league).toString();
    }

    private static String stage(String slug) {
        switch (slug) {
            case "final": return "Final";
            case "semifinals": return "Semifinal";
            case "quarterfinals": return "Cuartos";
            case "round-of-16": return "Octavos";
            case "round-of-32": return "16avos";
            case "round-of-64": return "32avos";
            case "knockout-round-playoffs": case "playoffs": case "knockout-playoffs": return "Playoffs";
            case "group-stage": return "Fase de grupos";
            case "league-phase": return "Fase liga";
            default: return "";
        }
    }

    static String league(String n) {
        if (n == null) return "";
        if (n.contains("Libertadores")) return "Libertadores";
        if (n.contains("Sudamericana")) return "Sudamericana";
        if (n.contains("Champions")) return "Champions";
        if (n.contains("Europa League")) return "Europa League";
        if (n.contains("Recopa")) return "Recopa";
        if (n.contains("Copa Argentina")) return "Copa Argentina";
        if (n.contains("Superliga") || n.contains("Copa de la Liga")) return "Copa de la Liga";
        if (n.contains("Trofeo de Campeones")) return "Trofeo de Campeones";
        if (n.contains("Supercopa Internacional")) return "Supercopa Internacional";
        if (n.contains("Supercopa")) return "Supercopa Argentina";
        if (n.contains("World Cup Qualifying")) return "Eliminatorias";
        if (n.contains("World Cup")) return "Mundial";
        if (n.contains("Copa Am")) return "Copa América";
        if (n.contains("Friendly")) return "Amistoso";
        if (n.contains("LPF") || n.contains("Liga Profesional")) return "Liga";
        return n;
    }

    /** "EN VIVO 63'", "Entretiempo", "Final", "Hoy 21:30", "Mañana 19:15", "Dom 19:15", "13/11 17:00". */
    static String status(Match m, long now) {
        if (m.live()) {
            if (m.detail.equalsIgnoreCase("HT")) return "Entretiempo";
            return m.clock.isEmpty() ? "EN VIVO" : "EN VIVO " + m.clock;
        }
        if (m.done()) return "Final";
        if (m.kickedOff(now)) return "Empezó " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(m.start));
        // ESPN marks an unconfirmed kickoff "TBD": the date is right, the hour isn't.
        boolean tbd = m.detail.contains("TBD");
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        a.setTimeInMillis(now);
        b.setTimeInMillis(m.start);
        int days = (int) Math.round((noon(b) - noon(a)) / (double) DAY);
        String hour = tbd ? "" : " " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(m.start));
        Locale es = new Locale("es", "AR");
        if (days == 0) return "Hoy" + hour;
        if (days == 1) return "Mañana" + hour;
        if (days > 1 && days < 7) {
            String d = new SimpleDateFormat("EEE", es).format(new Date(m.start)).replace(".", "");
            return Character.toUpperCase(d.charAt(0)) + d.substring(1) + hour;
        }
        return new SimpleDateFormat("d/M", es).format(new Date(m.start)) + hour;
    }

    private static final android.graphics.Paint CREST = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);

    /**
     * One game on one line, for the screensaver: crest, "River Plate vs Racing" (or the score), crest, then the status
     * (red while live). align: -1 starts at x, 0 centers on x, 1 ends at x.
     */
    /**
     * The games to show right now, as on the home screen: every game being played (three at a time, taking turns past
     * three); with none live, one of the coming ones, taking turns every 12 s.
     */
    static List<Match> shownNow(List<Match> ms, long now) {
        List<Match> out = new ArrayList<>();
        int live = 0;
        while (live < ms.size() && (ms.get(live).live() || ms.get(live).kickedOff(now))) live++;   // the live ones lead
        if (live > 0) {
            int page = (int) ((now / 12_000) % ((live + 2) / 3));
            for (int i = page * 3; i < Math.min(live, page * 3 + 3); i++) out.add(ms.get(i));
        } else if (!ms.isEmpty()) {
            out.add(ms.get((int) ((now / 12_000) % ms.size())));
        }
        return out;
    }

    static void drawLine(android.graphics.Canvas c, Match m, android.graphics.Paint text, int statusColor, float x, float base, int align) {
        boolean scored = m.live() || m.done();
        String line = m.home.shortName + (scored ? "  " + score(m) + "  " : "  vs  ") + m.away.shortName;
        String st = status(m, System.currentTimeMillis());
        float cap = Ui.cap(text), crest = cap * 1.9f, gap = text.getTextSize() * 0.35f;
        float lw = text.measureText(line), sw = text.measureText(st);
        float w = crest + gap + lw + gap + crest + 3 * gap + sw;
        float left = align < 0 ? x : align == 0 ? x - w / 2 : x - w, mid = base - cap / 2;
        drawCrest(c, m.home.crest, left, mid, crest, text.getAlpha());
        left += crest + gap;
        c.drawText(line, left, base, text);
        left += lw + gap;
        drawCrest(c, m.away.crest, left, mid, crest, text.getAlpha());
        left += crest + 3 * gap;
        int color = text.getColor();
        text.setColor(m.live() ? 0xFFFF453A : statusColor);
        c.drawText(st, left, base, text);
        text.setColor(color);
    }

    private static void drawCrest(android.graphics.Canvas c, Bitmap b, float x, float mid, float size, int alpha) {
        if (b == null) return;
        float h = size * b.getHeight() / Math.max(1f, b.getWidth());
        CREST.setAlpha(alpha);
        c.drawBitmap(b, null, new android.graphics.RectF(x, mid - h / 2, x + size, mid + h / 2), CREST);
    }

    private static long noon(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 12);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    /** Crests for both lists, four at a time; after the first run they come from memory or disk. */
    private static void loadCrests(File dir, Result r) throws InterruptedException {
        List<Team> teams = new ArrayList<>();
        for (List<Match> l : java.util.Arrays.asList(r.highlights, r.upcoming, r.featured)) {
            for (Match m : l) {
                teams.add(m.home);
                teams.add(m.away);
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (Team t : teams) {
            if (t.logo.isEmpty() || crests.containsKey(t.logo)) continue;
            String href = t.logo;
            crests.put(href, MISSING);   // claimed: fetched once even if several games share it
            pool.execute(() -> {
                Bitmap b = crest(dir, href);
                crests.put(href, b == null ? MISSING : b);
            });
        }
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
        for (Team t : teams) {
            Bitmap b = crests.get(t.logo);
            t.crest = b == MISSING ? null : b;
        }
    }

    private static final Bitmap MISSING = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);

    /** Crest at 96 px through ESPN's resizer, kept on disk. */
    private static Bitmap crest(File dir, String href) {
        dir.mkdirs();
        File f = new File(dir, Integer.toHexString(href.hashCode()) + ".png");
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return b;
        }
        String path = href.replaceFirst("^https?://[^/]+", "");
        byte[] data = bytes("https://a.espncdn.com/combiner/i?img=" + path + "&h=96&w=96");
        if (data == null) data = bytes(href);
        if (data == null) return null;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (b == null) return null;
        if (b.getWidth() > 128) b = Bitmap.createScaledBitmap(b, 96, Math.max(1, 96 * b.getHeight() / b.getWidth()), true);
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception ignored) {
        }
        return b;
    }

    private static long iso(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat(s.length() > 17 ? "yyyy-MM-dd'T'HH:mm:ss'Z'" : "yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s).getTime();
        } catch (Exception ex) {
            return 0;
        }
    }

    private static JSONObject json(String url) {
        byte[] b = bytes(url);
        if (b == null) return null;
        try {
            return new JSONObject(new String(b, "UTF-8"));
        } catch (Exception ex) {
            return null;
        }
    }

    private static byte[] bytes(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Exception ex) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
