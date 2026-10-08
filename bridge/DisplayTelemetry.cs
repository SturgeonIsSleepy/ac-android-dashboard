using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text.RegularExpressions;

sealed class DisplayTelemetry
{
    readonly string root;
    string carKey, trackKey;
    int gaugeMax;
    readonly List<float[]> zones = new List<float[]>();
    public DisplayTelemetry(string gameRoot) { root = gameRoot; }
    static bool Valid(string name) { return !String.IsNullOrEmpty(name) && !name.Contains("..") && name.IndexOfAny(new[] {'/', '\\', ':'}) < 0; }
    public int RpmScale(string car, int limiter)
    {
        if (car != carKey)
        {
            carKey = car; gaugeMax = 0;
            if (Valid(car))
            try
            {
                string folder = Path.Combine(root, "content", "cars", car);
                string packed = Path.Combine(folder, "data.acd"), text = null;
                var files = File.Exists(packed) ? AcdReader.Read(packed) : new Dictionary<string,string>();
                if (!files.TryGetValue("analog_instruments.ini", out text)) {
                    string loose = Path.Combine(folder, "data", "analog_instruments.ini");
                    if (File.Exists(loose)) text = File.ReadAllText(loose);
                }
                if (text != null)
                foreach (var section in Ini(text))
                {
                    if (!section["section"].ToUpperInvariant().Contains("RPM")) continue;
                    string value;
                    if (section.TryGetValue("MAX_VALUE", out value)) gaugeMax = Math.Max(gaugeMax, (int)Number(value));
                    if (!section.TryGetValue("LUT", out value)) continue;
                    string lut;
                    if (files.TryGetValue(value, out lut)) {
                        foreach (string line in lut.Split('\n')) gaugeMax = Math.Max(gaugeMax, (int)Number(line.Split('|')[0]));
                    } else foreach (Match point in Regex.Matches(value, @"(?:\(|\|)\s*(\d+(?:\.\d+)?)\s*="))
                        gaugeMax = Math.Max(gaugeMax, (int)Number(point.Groups[1].Value));
                }
            } catch (IOException) { gaugeMax = 0; }
        }
        return Math.Max(limiter, gaugeMax > 0 ? gaugeMax : (int)Math.Ceiling(limiter/1000.0)*1000);
    }
    public float DrsDistance(string track, string layout, float progress, float length, bool available)
    {
        string key = (track ?? "")+"/"+(layout ?? "");
        if (key != trackKey)
        {
            trackKey = key; zones.Clear();
            if (Valid(track) && (String.IsNullOrEmpty(layout) || Valid(layout)))
            {
                string folder = Path.Combine(root, "content", "tracks", track);
                if (!String.IsNullOrEmpty(layout)) folder = Path.Combine(folder, layout);
                string path = Path.Combine(folder, "data", "drs_zones.ini");
                try { if (File.Exists(path)) ReadZones(File.ReadAllText(path)); } catch (IOException) { }
            }
        }
        if (available) return 0;
        if (length <= 0 || progress < 0 || progress > 1 || zones.Count == 0) return -1;
        float best = Single.MaxValue;
        foreach (float[] zone in zones) {
            bool inside = zone[0] <= zone[1] ? progress >= zone[0] && progress <= zone[1] : progress >= zone[0] || progress <= zone[1];
            if (inside) return -1; // Inside a zone but game permission is absent.
            float gap = zone[0]-progress; if (gap < 0) gap += 1;
            best = Math.Min(best, gap*length);
        }
        return best;
    }
    internal void ReadZones(string text)
    {
        zones.Clear();
        foreach (var section in Ini(text)) {
            string start, end;
            if (!section["section"].StartsWith("ZONE_", StringComparison.OrdinalIgnoreCase) || !section.TryGetValue("START", out start) || !section.TryGetValue("END", out end)) continue;
            float a = Number(start), b = Number(end);
            if (a >= 0 && a <= 1 && b >= 0 && b <= 1 && a != b) zones.Add(new[] { a,b });
        }
    }
    static float Number(string text) { float value; return Single.TryParse(text.Trim(), NumberStyles.Float, CultureInfo.InvariantCulture, out value) ? value : -1; }
    static List<Dictionary<string,string>> Ini(string text)
    {
        var sections = new List<Dictionary<string,string>>(); Dictionary<string,string> current = null;
        foreach (string original in text.Split('\n')) {
            string line = original.Split(';')[0].Trim();
            if (line.StartsWith("[") && line.EndsWith("]")) {
                current = new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase); current["section"] = line.Trim('[',']'); sections.Add(current);
            } else if (current != null && line.Contains("=")) {
                int at = line.IndexOf('='); current[line.Substring(0,at).Trim()] = line.Substring(at+1).Trim();
            }
        }
        return sections;
    }
}
