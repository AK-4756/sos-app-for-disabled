package com.sosapp.disabled;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class ContactsActivity extends AppCompatActivity {

    private ListView listViewContacts;
    private Button btnAddContact;
    private TextView tvEmpty;

    private DatabaseHelper dbHelper;
    private List<Contact> contactList;
    private ArrayAdapter<String> adapter;
    private List<String> displayList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contacts);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Emergency Contacts");
        }

        dbHelper = new DatabaseHelper(this);
        contactList = new ArrayList<>();
        displayList = new ArrayList<>();

        listViewContacts = findViewById(R.id.listViewContacts);
        btnAddContact = findViewById(R.id.btnAddContact);
        tvEmpty = findViewById(R.id.tvEmpty);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayList);
        listViewContacts.setAdapter(adapter);

        btnAddContact.setOnClickListener(v -> showAddEditDialog(null));

        listViewContacts.setOnItemClickListener((parent, view, position, id) ->
                showContactOptions(position));

        loadContacts();
    }

    private void applyTheme() {
        android.content.SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean isDark = prefs.getBoolean(SettingsActivity.KEY_DARK_MODE, true);
        setTheme(isDark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
    }

    private void loadContacts() {
        contactList = dbHelper.getAllContacts();
        displayList.clear();
        for (Contact c : contactList) {
            displayList.add(c.getName() + "\n" + c.getPhone());
        }
        adapter.notifyDataSetChanged();

        if (contactList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            listViewContacts.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            listViewContacts.setVisibility(View.VISIBLE);
        }
    }

    private void showAddEditDialog(final Contact existingContact) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(existingContact == null ? "Add Emergency Contact" : "Edit Contact");

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_contact, null);
        builder.setView(dialogView);

        EditText etName = dialogView.findViewById(R.id.etContactName);
        EditText etPhone = dialogView.findViewById(R.id.etContactPhone);

        if (existingContact != null) {
            etName.setText(existingContact.getName());
            etPhone.setText(existingContact.getPhone());
        }

        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = etName.getText().toString().trim();
            String phone = etPhone.getText().toString().trim();

            if (TextUtils.isEmpty(name)) {
                Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show();
                return;
            }
            if (TextUtils.isEmpty(phone)) {
                Toast.makeText(this, "Phone number is required", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!phone.matches("[+]?[0-9\\s\\-]{7,15}")) {
                Toast.makeText(this, "Enter a valid phone number", Toast.LENGTH_SHORT).show();
                return;
            }

            if (existingContact == null) {
                Contact newContact = new Contact(name, phone);
                dbHelper.addContact(newContact);
                Toast.makeText(this, name + " added", Toast.LENGTH_SHORT).show();
            } else {
                existingContact.setName(name);
                existingContact.setPhone(phone);
                dbHelper.updateContact(existingContact);
                Toast.makeText(this, "Contact updated", Toast.LENGTH_SHORT).show();
            }
            loadContacts();
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showContactOptions(int position) {
        Contact contact = contactList.get(position);
        String[] options = {"Edit", "Delete"};

        new AlertDialog.Builder(this)
                .setTitle(contact.getName())
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showAddEditDialog(contact);
                    } else {
                        confirmDelete(contact);
                    }
                })
                .show();
    }

    private void confirmDelete(Contact contact) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Contact")
                .setMessage("Remove " + contact.getName() + " from emergency contacts?")
                .setPositiveButton("Delete", (d, w) -> {
                    dbHelper.deleteContact(contact.getId());
                    Toast.makeText(this, contact.getName() + " removed", Toast.LENGTH_SHORT).show();
                    loadContacts();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dbHelper != null) dbHelper.close();
    }
}
