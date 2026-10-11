using System.Drawing.Printing;

namespace Bunah.App;

/// <summary>
/// Prints to the driver-installed thermal queue via RAW passthrough.
/// No COM port selection needed — pick the printer from the list.
/// </summary>
public sealed class WindowsPrinterService
{
    public static List<string> ListPrinters()
    {
        var list = new List<string>();
        foreach (string p in PrinterSettings.InstalledPrinters) list.Add(p);
        list.Sort(StringComparer.OrdinalIgnoreCase);
        return list;
    }

    public void Print(string printerName, byte[] escPosBytes) =>
        RawPrinterHelper.SendBytesToPrinter(printerName, escPosBytes);
}
