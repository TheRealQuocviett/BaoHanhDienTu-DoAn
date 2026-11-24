package vn.baohanh.service;

import vn.baohanh.db.Database;
import vn.baohanh.print.PrintService;

/**
 * Gom toàn bộ dịch vụ nghiệp vụ. Giao diện web (ApiServer) và giao diện Swing
 * dùng chung một AppContext nên dữ liệu và quy tắc luôn giống nhau.
 */
public class AppContext {
    public final Database db;
    public final AuditService audit;
    public final Auth auth;
    public final CatalogService catalog;
    public final WarrantyService warranty;
    public final InventoryService inventory;
    public final SettingService settings;
    public final ReportService reports;
    public final PortalService portal;
    public final PrintService print;
    public final CustomerService customers;
    public final RequestService requests;
    public final PhotoService photos;
    public final BackupService backup;
    public final vn.baohanh.notify.NotificationService notify;
    public final MessageService messages;
    public final CalendarService calendar;
    public final PurchaseService purchases;
    public final FaultService faults;
    public final ImportService imports;
    public final ScanService scan;
    public final PermissionService permissions;

    public AppContext(Database db, int port) {
        this.db = db;
        this.audit = new AuditService(db);
        this.auth = new Auth(db, audit);
        this.catalog = new CatalogService(db, audit, auth);
        this.warranty = new WarrantyService(db, audit);
        this.inventory = new InventoryService(db, audit);
        this.settings = new SettingService(db, audit);
        this.warranty.setSettings(settings);
        this.settings.setPort(port);
        this.reports = new ReportService(db);
        this.portal = new PortalService(db, settings);
        this.photos = new PhotoService(db, audit);
        this.print = new PrintService(warranty, settings, photos);
        this.customers = new CustomerService(db, warranty, audit);
        this.requests = new RequestService(db, warranty, audit);
        this.backup = new BackupService(db, photos, audit);
        this.notify = new vn.baohanh.notify.NotificationService(db, settings, audit);
        this.warranty.setNotifier(notify);
        this.requests.setNotifier(notify);
        this.requests.setPhotos(photos);
        this.messages = new MessageService(db, audit);
        this.messages.setNotifier(notify);
        this.calendar = new CalendarService(db);
        this.purchases = new PurchaseService(db, audit);
        this.print.setPurchases(purchases);
        this.customers.setPhotos(photos);
        this.faults = new FaultService(db);
        this.imports = new ImportService(db, catalog, audit);
        this.scan = new ScanService(settings);
        this.permissions = new PermissionService(db, audit);
    }
}
