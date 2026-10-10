using System;
using System.Globalization;
using System.IO;
using System.Text;

sealed class MirrorSettings
{
    public static readonly string[] Names = { "视野角（上下）", "水平朝向", "俯仰", "左右偏移", "高度偏移", "前后偏移" };
    public static readonly float[] Minimum = { 20,-60,-35,-2,-1,-3 }, Maximum = { 100,60,35,2,1,3 }, Step = { 1,1,1,.02f,.02f,.05f };
    readonly string path;
    readonly float[][] values = { Defaults(1),Defaults(2),Defaults(3) };
    public volatile byte[] Packet;
    public volatile string Text;
    public volatile string Error = "";
    public volatile byte[] ErrorPacket;
    int revision;
    public static float[] Defaults(int view) { return new float[] { 50,view == 1 ? -10.2f : view == 3 ? 10.2f : 0,0,0,0,0 }; }
    public MirrorSettings(string filename) {
        path = filename;
        try { if (File.Exists(path)) foreach (string line in File.ReadAllLines(path)) {
            string[] fields = line.Split('=',','); int view;
            if (fields.Length != 7 || !int.TryParse(fields[0],out view) || view < 1 || view > 3) continue;
            for (int i = 0;i < 6;i++) { float value; if (float.TryParse(fields[i+1],NumberStyles.Float,CultureInfo.InvariantCulture,out value) && Valid(value)) values[view-1][i] = Clamp(i,value); }
        } } catch (IOException) { Error = "配置读取失败，正在使用默认值"; }
        catch (UnauthorizedAccessException) { Error = "配置读取失败，正在使用默认值"; }
        Publish();
    }
    static bool Valid(float value) { return !float.IsNaN(value) && !float.IsInfinity(value); }
    static float Clamp(int field,float value) { return Math.Max(Minimum[field],Math.Min(Maximum[field],value)); }
    public float[] Get(int view) { lock (values) return (float[])values[view-1].Clone(); }
    public void Set(int view,int field,float value) {
        if (view < 1 || view > 3 || field < 0 || field > 5 || !Valid(value)) return;
        lock (values) { var previous = (float[])values[view-1].Clone(); values[view-1][field] = Clamp(field,value); Save(view,previous); }
    }
    public void Reset(int view) { if (view < 1 || view > 3) return; lock (values) { var previous = values[view-1]; values[view-1] = Defaults(view); Save(view,previous); } }
    void Save(int view,float[] previous) {
        try {
            string next = path+".next"; File.WriteAllText(next,Serialize(),new UTF8Encoding(false));
            if (File.Exists(path)) File.Replace(next,path,null); else File.Move(next,path);
            Error = "";
        } catch (IOException) { values[view-1] = previous; Error = "配置未保存，请检查文件写入权限"; }
        catch (UnauthorizedAccessException) { values[view-1] = previous; Error = "配置未保存，请检查文件写入权限"; }
        Publish();
    }
    string Serialize() {
        var text = new StringBuilder();
        for (int view = 0;view < 3;view++) {
            text.Append(view+1).Append('=');
            for (int field = 0;field < 6;field++) {
                if (field != 0) text.Append(',');
                text.Append(values[view][field].ToString("R",CultureInfo.InvariantCulture));
            }
            text.Append('\n');
        }
        return text.ToString();
    }
    void Publish() {
        byte[] packet = new byte[84], status = new byte[12];
        Array.Copy(BitConverter.GetBytes(0x31464341),0,packet,0,4); Array.Copy(BitConverter.GetBytes(8),0,packet,4,4);
        Array.Copy(BitConverter.GetBytes(++revision),0,packet,8,4);
        for (int view = 0;view < 3;view++) for (int field = 0;field < 6;field++) Array.Copy(BitConverter.GetBytes(values[view][field]),0,packet,12+(view*6+field)*4,4);
        Array.Copy(packet,status,4); Array.Copy(BitConverter.GetBytes(10),0,status,4,4); Array.Copy(BitConverter.GetBytes(Error.Length == 0 ? 0 : 1),0,status,8,4);
        Text = Serialize(); Packet = packet; ErrorPacket = status;
    }
}
