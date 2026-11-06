package br.com.fatura;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;

@Component
class Reminders {
    final Users users; final BillService service; final JavaMailSender mail;
    @Value("${app.mail.enabled}") boolean enabled;
    @Value("${app.mail.from}") String from;
    Reminders(Users users, BillService service, JavaMailSender mail) { this.users=users; this.service=service; this.mail=mail; }
    @Scheduled(cron="0 */15 * * * *",zone="America/Sao_Paulo")
    public void send() {
        if(!enabled) return;
        users.findAll().forEach(user -> service.remind(user.id,mail,from));
    }
}
