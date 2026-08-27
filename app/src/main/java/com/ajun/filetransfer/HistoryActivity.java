package com.ajun.filetransfer;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class HistoryActivity extends AppCompatActivity {

    private ListView lvHistory;
    private Button btnClear;
    private ArrayAdapter<String> adapter;
    private List<String> historyList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        lvHistory = findViewById(R.id.lv_history);
        btnClear = findViewById(R.id.btn_clear_history);

        // 返回按钮（使用 TextView 模拟）
        findViewById(R.id.iv_back).setOnClickListener(v -> finish());

        // 加载历史
        loadHistory();

        // 清空历史
        btnClear.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences("history", MODE_PRIVATE);
            prefs.edit().remove("history").apply();
            historyList.clear();
            adapter.notifyDataSetChanged();
            Toast.makeText(this, "历史已清空", Toast.LENGTH_SHORT).show();
        });
    }

    private void loadHistory() {
        SharedPreferences prefs = getSharedPreferences("history", MODE_PRIVATE);
        String json = prefs.getString("history", "[]");
        historyList.clear();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = array.length() - 1; i >= 0; i--) { // 倒序显示（最新在前）
                historyList.add(array.getString(i));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, historyList);
        lvHistory.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory(); // 每次返回刷新
    }
}