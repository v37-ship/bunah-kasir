namespace Bunah.Receipt;

/// <summary>One receipt line. Qty > 1 prints as "Name xN".</summary>
public sealed record ReceiptItem(string Name, string Desc, long Price)
{
    public int Qty { get; init; } = 1;
    public long LineTotal => Price * Qty;
    public string Label => Qty > 1 ? $"{Name} x{Qty}" : Name;
}

public sealed record StoreInfo(string Name, string Tagline, string Address, string Hours, string Contact);

public sealed record Receipt(
    StoreInfo Store,
    /// <summary>Order number. User-editable so a revised receipt can reuse/increment it manually.</summary>
    string OrderNo,
    string DateTimeText,
    string CustomerName,
    string OrderType,
    string TableNo,
    List<ReceiptItem> Items,
    string DiscountType,
    long DiscountValue,
    string PaymentStatus,
    List<string> FooterLines)
{
    /// <summary>Sum of line totals — single source of truth, so callers cannot disagree.</summary>
    public long Subtotal => Items.Sum(i => i.LineTotal);

    /// <summary>Payable amount after discount. Never negative.</summary>
    public long GrandTotal => Subtotal - ReceiptTextFormatter.DiscountAmount(Subtotal, DiscountType, DiscountValue);
}
