package vn.baohanh.service;

/** Nơi nhận sự kiện nghiệp vụ để gửi thông báo cho khách (do NotificationService cài đặt). */
public interface Notifier {
    void onTicket(String event, long ticketId);

    void onRequest(String event, long requestId);

    /** Nhân viên vừa trả lời tin nhắn của khách trong phiếu. */
    default void onMessage(long ticketId, long messageId, String body) { }

    Notifier NONE = new Notifier() {
        public void onTicket(String event, long ticketId) { }
        public void onRequest(String event, long requestId) { }
    };
}
