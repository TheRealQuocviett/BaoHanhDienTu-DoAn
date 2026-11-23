package vn.baohanh.service;

/** Nơi nhận sự kiện nghiệp vụ để gửi thông báo cho khách (do NotificationService cài đặt). */
public interface Notifier {
    void onTicket(String event, long ticketId);

    void onRequest(String event, long requestId);

    Notifier NONE = new Notifier() {
        public void onTicket(String event, long ticketId) { }
        public void onRequest(String event, long requestId) { }
    };
}
