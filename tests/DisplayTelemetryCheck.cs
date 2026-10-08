using System;

class DisplayTelemetryCheck
{
    static void Check(bool value, string message) { if (!value) throw new Exception(message); }
    static void Near(float actual, float expected) { Check(Math.Abs(actual-expected) < .5f, "Distance: "+actual+" expected "+expected); }
    static void Main()
    {
        string root = @"D:\Program Files\steam\steamapps\common\assettocorsa";
        var display = new DisplayTelemetry(root);
        Check(display.RpmScale("ks_maserati_alfieri",7500) == 9000, "Actual car RPM LUT ends at 9000, limiter at 7500");
        Check(display.RpmScale("../not-a-car",7500) == 8000, "Unknown scale falls back without leaving car directory");
        Near(display.DrsDistance("monza","",.02f,5755,false), .009f*5755);
        Near(display.DrsDistance("monza","",.9f,5755,false), .129f*5755);
        Check(display.DrsDistance("monza","",.10f,5755,false) == -1, "Ineligible inside-zone does not claim DRS readiness");
        Check(display.DrsDistance("monza","",.10f,5755,true) == 0, "Game permission wins at the zone");
        Check(display.DrsDistance("../invalid","",.2f,5755,false) == -1, "No invented zones on invalid track");
        display.DrsDistance("monza","",.2f,5755,false);
        display.ReadZones("[ZONE_0]\nSTART=0.9\nEND=0.1\n");
        Check(display.DrsDistance("monza","",.99f,1000,false) == -1, "Wrapped zone contains finish line");
        Near(display.DrsDistance("monza","",.5f,1000,false),400);
        Console.WriteLine("Actual ACD gauge, real DRS zones, permissions, wrapped zones and path checks passed");
    }
}
