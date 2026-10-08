// Read-only C# port of the ACD reader in philippkosarev/acd.py 1.0.0.
// Modified 2026-10-07. GPL-2.0; see licenses/acd-GPL-2.0.txt.
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

static class AcdReader
{
    static byte[] Key(string folder)
    {
        byte[] name = Encoding.UTF8.GetBytes(folder.ToLowerInvariant());
        int n = name.Length;
        int[] parts = new int[8];
        foreach (byte b in name) parts[0] += b;
        for (int i = 0; i < n-1; i += 2) parts[1] = (parts[1]*name[i]-name[i+1]) % 256;
        for (int i = 1; i < n-3; i += 3) parts[2] = parts[2]*name[i]/(name[i+1]+27)-27-name[i-1];
        parts[3] = 131;
        for (int i = 1; i < n; i++) parts[3] -= name[i];
        parts[4] = 66;
        for (int i = 1; i < n-4; i += 4) parts[4] = (parts[4]*(name[i]+15)*(name[i-1]+15)+22) % 256;
        parts[5] = 101; parts[6] = 171; parts[7] = 171;
        for (int i = 0; i < n-2; i += 2) { parts[5] -= name[i]; parts[6] %= name[i]; }
        for (int i = 0; i < n-1; i++) parts[7] = parts[7]/name[i]+name[i+1];
        string[] values = new string[8];
        for (int i = 0; i < 8; i++) values[i] = ((parts[i]%256+256)%256).ToString();
        return Encoding.ASCII.GetBytes(String.Join("-", values));
    }
    public static Dictionary<string, string> Read(string path)
    {
        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        byte[] key = Key(Path.GetFileName(Path.GetDirectoryName(path)));
        using (var r = new BinaryReader(File.OpenRead(path)))
        {
            if (r.ReadInt32() == -1111) r.ReadInt32(); else r.BaseStream.Position = 0;
            while (r.BaseStream.Position < r.BaseStream.Length)
            {
                int size = r.ReadInt32();
                if (size < 1 || size > 256) throw new InvalidDataException("ACD filename length");
                string name = Encoding.UTF8.GetString(r.ReadBytes(size));
                int length = r.ReadInt32();
                if (length < 0 || length > (r.BaseStream.Length-r.BaseStream.Position)/4)
                    throw new InvalidDataException("ACD entry length");
                if (!name.EndsWith(".ini") && !name.EndsWith(".lut")) { r.BaseStream.Seek(length*4L, SeekOrigin.Current); continue; }
                byte[] data = new byte[length];
                for (int i = 0; i < length; i++) data[i] = unchecked((byte)(r.ReadInt32()-key[i%key.Length]));
                result[name] = Encoding.UTF8.GetString(data);
            }
        }
        return result;
    }
}
