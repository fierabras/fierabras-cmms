package com.grash.integration.support;

import com.grash.dto.EmailAttachmentDTO;
import com.grash.model.User;
import com.grash.service.MailService;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mail double: records every call, named after the {@link MailService} method, and never sends anything.
 */
public class MailDouble extends CallRecorder implements MailService {

    @Override
    public void sendSimpleMessage(String[] to, String subject, String text) {
        record("sendSimpleMessage", to, subject, text);
    }

    @Override
    public void sendMessageWithAttachment(String to, String subject, String text, String attachmentName,
                                          byte[] attachmentData, String attachmentType) {
        record("sendMessageWithAttachment", to, subject, text, attachmentName, attachmentData, attachmentType);
    }

    @Override
    public void sendMessageUsingThymeleafTemplate(String[] to, String subject, Map<String, Object> templateModel,
                                                  String template, Locale locale,
                                                  List<EmailAttachmentDTO> attachmentDTOS) {
        record("sendMessageUsingThymeleafTemplate", to, subject, templateModel, template, locale, attachmentDTOS);
    }

    @Override
    public void sendHtmlMessage(String[] to, String subject, String htmlBody, List<EmailAttachmentDTO> attachmentDTOS) {
        record("sendHtmlMessage", to, subject, htmlBody, attachmentDTOS);
    }

    @Override
    public void sendMailToSuperAdmins(String subject, String text) {
        record("sendMailToSuperAdmins", subject, text);
    }

    @Override
    public void removeUserFromContactList(String email) {
        record("removeUserFromContactList", email);
    }

    @Override
    public void addToContactList(User user) {
        record("addToContactList", user);
    }
}
