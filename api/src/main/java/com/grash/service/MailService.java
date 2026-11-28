package com.grash.service;

import com.grash.dto.EmailAttachmentDTO;
import com.grash.model.User;
import jakarta.mail.MessagingException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface MailService {
    ThreadLocal<Boolean> skipMail = ThreadLocal.withInitial(() -> false);

    void sendSimpleMessage(String[] to, String subject, String text);

    void sendMessageWithAttachment(String to, String subject, String text, String attachmentName,
                                   byte[] attachmentData, String attachmentType);

    /**
     * Sends the mail off the thread of the caller (P-23). The result completes when the mail is sent, or
     * exceptionally when it failed; the call itself throws a {@code TaskRejectedException} when the sends are full.
     */
    CompletableFuture<Void> sendMessageUsingThymeleafTemplate(String[] to, String subject,
                                                              Map<String, Object> templateModel, String template,
                                                              Locale locale, List<EmailAttachmentDTO> attachmentDTOS);

    /**
     * P-23: called on the service itself, this runs the mail in the thread of the caller; use the six-argument
     * method, through {@code ExternalSendService.mail}.
     */
    default CompletableFuture<Void> sendMessageUsingThymeleafTemplate(String[] to, String subject,
                                                                      Map<String, Object> templateModel,
                                                                      String template, Locale locale) {
        return sendMessageUsingThymeleafTemplate(to, subject, templateModel, template, locale, null);
    }

    void sendHtmlMessage(String[] to, String subject, String htmlBody, List<EmailAttachmentDTO> attachmentDTOS) throws MessagingException, java.io.IOException;

    default void sendHtmlMessage(String[] to, String subject, String htmlBody) throws MessagingException, IOException {
        sendHtmlMessage(to, subject, htmlBody, null);
    }

    void sendMailToSuperAdmins(String subject, String text);

    void removeUserFromContactList(String email);

    void addToContactList(User user);
}
