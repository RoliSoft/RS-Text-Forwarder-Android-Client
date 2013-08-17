package net.rolisoft.textforwarder;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

public abstract class ContactTools {

    public static List<Contact> _contacts = null;
    public static String _cc = null;

    public static String formatNumber(Context context, String number)
    {
        if (_cc == null) {
            TelephonyManager tm = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
            _cc = tm.getSimCountryIso();

            if (_cc == null) {
                _cc = "us";
            }
        }

        PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
        PhoneNumber phoneNumber = null;

        try {
            phoneNumber = phoneUtil.parse(number, _cc);
        } catch (Exception ex) {
            return number;
        }

        String fmt, cc = phoneUtil.getRegionCodeForNumber(phoneNumber);

        if (cc == null || !cc.equalsIgnoreCase(_cc)) {
            fmt = phoneUtil.format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL);
        } else {
            fmt = phoneUtil.format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.NATIONAL);
        }

        return fmt != null ? fmt : number;
    }

    public static Contact findContact(Context context, String query, boolean fullOnly)
    {
        return findContact(context, query, -1, fullOnly);
    }

    public static Contact findContact(Context context, String query, int preferredNumber, boolean fullOnly)
    {
        Contact contact;

        if ((contact = findContactNumber(context, query, fullOnly)) != null) {
            return contact;
        }

        return findContactName(context, query, preferredNumber);
    }

    public static Contact findContactName(Context context, String name)
    {
        return findContactName(context, name, -1);
    }

    public static Contact findContactName(Context context, String name, int preferredNumber)
    {
        List<Contact> contacts = getContacts(context);

        if (contacts == null) {
            return null;
        }

        name = createSlug(name);

        for (Contact contact : contacts) {
            if (createSlug(contact.name).contains(name)) {
                if (preferredNumber != -1) {
                    if (preferredNumber > 0 && preferredNumber <= contact.numbers.size()) {
                        contact.selected = contact.numbers.get(preferredNumber - 1);
                        return contact;
                    }
                } else {
                    contact.selected = contact.preferred;
                    return contact;
                }
            }
        }

        return null;
    }

    public static Contact findContactNumber(Context context, String phone, boolean fullOnly)
    {
        List<Contact> contacts = getContacts(context);

        if (contacts == null) {
            return null;
        }

        if (!isPhoneNumber(phone)) {
            return null;
        }

        phone = cleanNumber(phone);

        for (Contact contact : contacts) {
            for (Contact.Number number : contact.numbers) {
                if ((fullOnly && number.number.endsWith(phone) && Math.abs(number.number.replaceFirst("\\+", "").length() - phone.length()) < 3)
                || (!fullOnly && number.number.contains(phone))) {
                    contact.selected = number;
                    return contact;
                }
            }
        }

        return null;
    }

    public static boolean isPhoneNumber(String number)
    {
        return number.matches(".*[0-9]{3,}.*") && !number.matches(".*[A-Za-z].*");
    }

    public static String cleanNumber(String number)
    {
        return number.replaceAll("[^0-9]", "");
    }

    public static String createSlug(String name)
    {
        return Normalizer.normalize(name.toLowerCase(), Normalizer.Form.NFKD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase().replaceAll("([\"']|^([^a-z0-9]+)|([^a-z0-9]+)$)", "").replaceAll("[^a-z0-9]", ".").replaceAll("\\.{2,}", ".");
    }

    public static String createXmppAddrAutoSelCheck(Context context, Contact contact)
    {
        int sel;

        if (contact.selected == null || contact.selected == contact.preferred) {
            sel = -1;
        } else {
            sel = contact.numbers.indexOf(contact.selected) + 1;

            if (sel == 0) {
                sel = -1;
            }
        }

        return createXmppAddrCheck(context, contact, sel);
    }

    public static String createXmppAddrCheck(Context context, Contact contact, int sel)
    {
        String addr = createSlug(contact.name);
        if (sel != -1) {
            addr += "-" + sel;
        }

        Contact rev = resolveXmppAddr(context, addr);
        if (!rev.selected.number.contentEquals(contact.selected.number)) {
            addr = cleanNumber(contact.selected.number);
        }

        return addr;
    }

    public static String createXmppAddr(Context context, Contact contact, int sel)
    {
        String addr = createSlug(contact.name);
        if (sel != -1) {
            addr += "-" + sel;
        }
        return addr;
    }

    public static Contact resolveXmppAddr(Context context, String addr)
    {
        int sel = -1;
        if (addr.contains("-")) {
            String[] mc = addr.split("/(?!.*\\-)", 2);
            if (mc.length > 1) {
                try {
                    sel = Integer.parseInt(mc[1].trim());
                    addr = mc[0].trim();
                } catch (Exception ex) { }
            }
        }

        return findContact(context, addr, sel, true);
    }

    public static List<Contact> getContacts(Context context)
    {
        if (_contacts != null) {
            return _contacts;
        }

        List<Contact> contacts = new ArrayList<Contact>();

        Cursor cur = context.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);

        if (cur == null) {
            return contacts;
        }

        try {
            while (cur.moveToNext()) {
                String id = cur.getString(cur.getColumnIndex(ContactsContract.Contacts._ID));
                String key = cur.getString(cur.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY));
                String name = cur.getString(cur.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));

                if (Integer.parseInt(cur.getString(cur.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER))) > 0) {
                    Contact contact = new Contact(key, name);
                    contacts.add(contact);

                    Cursor pCur = context.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", new String[] { id }, null);

                    if (pCur == null) {
                        continue;
                    }

                    try {
                        while (pCur.moveToNext()) {
                            String number = pCur.getString(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER));
                            if (number == null || number.contentEquals("")) {
                                number = pCur.getString(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)).replaceAll("[^0-9\\+\\*#]", "");
                            }

                            int type = Integer.parseInt(pCur.getString(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)));
                            String typeStr = (String)ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.getResources(), type, pCur.getString(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)));
                            boolean isDef = Integer.parseInt(pCur.getString(pCur.getColumnIndex(ContactsContract.Data.IS_SUPER_PRIMARY))) > 0;
                            boolean stop = false;

                            if (contact.numbers.size() >= 1) {
                                for (Contact.Number num2 : contact.numbers) {
                                    if (num2.number.contentEquals(number)) {
                                        if (isDef) {
                                            num2.isDefault = true;
                                            contact.preferred = num2;
                                        }

                                        stop = true;
                                    }
                                }
                            }

                            if (stop) {
                                continue;
                            }

                            Contact.Number numObj = contact.addNumber(number, typeStr, isDef);

                            if (isDef) {
                                contact.preferred = numObj;
                            } else if (contact.preferred == null && type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                                contact.preferred = numObj;
                            }
                        }

                        if (contact.numbers.size() == 0) {
                            contacts.remove(contact);
                        } else if (contact.preferred == null) {
                            contact.preferred = contact.numbers.get(0);
                        }
                    } finally {
                        pCur.close();
                    }
                }
            }
        } finally {
            cur.close();
        }

        return _contacts = contacts;
    }

}
