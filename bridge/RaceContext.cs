using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;

sealed class RaceContext
{
    readonly string path;
    long readAt;
    DateTime writtenAt;
    string car = "", track = "";
    int laps, sector, count;
    public int Cars = 1;
    public float PitLimit;
    public float Grip = -1, Wetness = -1, Water = -1, FuelPerLap;
    public bool EnvironmentFresh;
    public readonly int[] Personal = new int[64], World = new int[64];
    public RaceContext(string root) { path = Path.Combine(root,"apps","lua","acflip_relay","race-context.txt"); }
    public bool Read(string currentCar, string currentTrack, int currentLaps, int currentSector)
    {
        long now = DateTime.UtcNow.Ticks;
        if (now-readAt > 500000) {
            readAt = now;
            try {
                if (File.Exists(path)) {
                    using (var file = new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.ReadWrite | FileShare.Delete))
                    using (var reader = new StreamReader(file)) Parse(reader.ReadToEnd(),File.GetLastWriteTimeUtc(path));
                }
            } catch (IOException) { }
        }
        bool matches = car == currentCar && track == currentTrack;
        EnvironmentFresh = matches && (DateTime.UtcNow-writtenAt).TotalSeconds < 2;
        if (!matches) { Cars = 1; PitLimit = 0; }
        return EnvironmentFresh && count > 0 && laps == currentLaps && sector == currentSector;
    }
    internal bool Parse(string text, DateTime stamp)
    {
        if (!text.EndsWith("end=ACFLIP1\n",StringComparison.Ordinal)) return false;
        var values = new Dictionary<string,string>();
        foreach (string line in text.Split('\n')) { int at = line.IndexOf('='); if (at > 0) values[line.Substring(0,at)] = line.Substring(at+1).Trim(); }
        string value;
        if (!values.TryGetValue("version",out value) || value != "1") return false;
        int n, newLaps, newSector, cars; float limit;
        string personal, world, newCar, newTrack;
        if (!values.TryGetValue("count",out value) || !Int32.TryParse(value,out n) || n < 1 || n > 64 ||
            !values.TryGetValue("car",out newCar) || !values.TryGetValue("track",out newTrack) ||
            !values.TryGetValue("laps",out value) || !Int32.TryParse(value,out newLaps) ||
            !values.TryGetValue("sector",out value) || !Int32.TryParse(value,out newSector) ||
            !values.TryGetValue("cars",out value) || !Int32.TryParse(value,out cars) || cars < 1 ||
            !values.TryGetValue("limit",out value) || !Single.TryParse(value,NumberStyles.Float,CultureInfo.InvariantCulture,out limit) || Single.IsNaN(limit) || Single.IsInfinity(limit) || limit < 0 || limit > 200 ||
            !values.TryGetValue("personal",out personal) || !values.TryGetValue("world",out world)) return false;
        string[] p = personal.Split(','), w = world.Split(','); int[] ps = new int[64], ws = new int[64];
        float grip, wetness, water, fuel;
        if (!OptionalFloat(values,"grip",-1,1,out grip) || !OptionalFloat(values,"wetness",-1,1,out wetness) ||
            !OptionalFloat(values,"water",-1,1,out water) || !OptionalFloat(values,"fuelPerLap",0,1000,out fuel)) return false;
        if (p.Length != n || w.Length != n) return false;
        for (int i = 0; i < n; i++) if (!Int32.TryParse(p[i],out ps[i]) || !Int32.TryParse(w[i],out ws[i]) || ps[i] < 0 || ws[i] < 0) return false;
        car = newCar; track = newTrack; laps = newLaps; sector = newSector; count = n; Cars = cars; PitLimit = limit; writtenAt = stamp;
        Grip = grip; Wetness = wetness; Water = water; FuelPerLap = fuel;
        Array.Copy(ps,Personal,64); Array.Copy(ws,World,64); return true;
    }
    static bool OptionalFloat(Dictionary<string,string> values, string key, float missing, float maximum, out float result)
    {
        string value; result = missing;
        return !values.TryGetValue(key,out value) || Single.TryParse(value,NumberStyles.Float,CultureInfo.InvariantCulture,out result)
            && !Single.IsNaN(result) && !Single.IsInfinity(result) && result >= missing && result <= maximum;
    }
}
