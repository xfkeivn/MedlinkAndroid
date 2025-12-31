package com.bsci.medlink.ui.registration

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bsci.medlink.R
import com.bsci.medlink.data.local.PreferenceManager
import com.bsci.medlink.data.model.Department
import com.bsci.medlink.data.model.Equipment
import com.bsci.medlink.data.model.Hospital
import com.bsci.medlink.data.model.HostRegistrationInfo
import com.bsci.medlink.data.remote.HostRegistrationService
import com.bsci.medlink.databinding.ActivityHostRegistrationBinding
import com.bsci.medlink.ui.meeting.MeetingPrepareActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HostRegistrationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHostRegistrationBinding
    private lateinit var preferenceManager: PreferenceManager
    private val registrationService = HostRegistrationService()
    private var serverIp: String = ""
    private var deviceUuid: String = ""
    
    private var hospitals: List<Hospital> = emptyList()
    private var departments: List<Department> = emptyList()
    private var equipments: List<Equipment> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)
        
        serverIp = intent.getStringExtra("server_ip") ?: getString(R.string.server_ip)
        deviceUuid = intent.getStringExtra("device_uuid") ?: ""

        if (serverIp.isEmpty() || deviceUuid.isEmpty()) {
            Toast.makeText(this, "缺少必要参数", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupViews()
        loadInitialInfo()
    }

    private fun setupViews() {
        binding.btnRegister.setOnClickListener {
            registerHost()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }
    }
    
    private fun loadInitialInfo() {
        binding.progressBar.visibility = View.VISIBLE
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = registrationService.getHostInitialInfo(serverIp)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    
                    if (response.success) {
                        hospitals = response.hospitals
                        departments = response.departments
                        equipments = response.equipments
                        
                        setupSpinners()
                    } else {
                        Toast.makeText(this@HostRegistrationActivity, response.message ?: "获取初始信息失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@HostRegistrationActivity, "获取初始信息失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun setupSpinners() {
        // 设置医院下拉列表
        val hospitalNames = hospitals.map { it.chinese_name }
        val hospitalAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, hospitalNames)
        hospitalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerHospital.adapter = hospitalAdapter
        
        // 设置科室下拉列表
        val departmentNames = departments.map { it.chinese_name }
        val departmentAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, departmentNames)
        departmentAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDepartment.adapter = departmentAdapter
        
        // 设置设备类型下拉列表
        val equipmentNames = equipments.map { it.name }
        val equipmentAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, equipmentNames)
        equipmentAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerEquipment.adapter = equipmentAdapter
    }

    private fun registerHost() {
        val hospitalIndex = binding.spinnerHospital.selectedItemPosition
        val departmentIndex = binding.spinnerDepartment.selectedItemPosition
        val equipmentIndex = binding.spinnerEquipment.selectedItemPosition
        val description = binding.etDescription.text.toString().trim()

        if (hospitalIndex < 0 || hospitalIndex >= hospitals.size) {
            Toast.makeText(this, "请选择医院", Toast.LENGTH_SHORT).show()
            return
        }

        if (departmentIndex < 0 || departmentIndex >= departments.size) {
            Toast.makeText(this, "请选择科室", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (equipmentIndex < 0 || equipmentIndex >= equipments.size) {
            Toast.makeText(this, "请选择设备类型", Toast.LENGTH_SHORT).show()
            return
        }
        
        val hospital = hospitals[hospitalIndex].chinese_name
        val department = departments[departmentIndex].chinese_name
        val equipment = equipments[equipmentIndex].name

        binding.progressBar.visibility = View.VISIBLE
        binding.btnRegister.isEnabled = false

        val registrationInfo = HostRegistrationInfo(
            hospital = hospital,
            department = department,
            equipment = equipment,
            description = if (description.isEmpty()) null else description
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = registrationService.registerHost(serverIp, deviceUuid, registrationInfo)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRegister.isEnabled = true

                    if (response.success && response.isRegistered) {
                        // 保存注册状态
                        preferenceManager.setHostRegistered(true)
                        preferenceManager.saveServerIp(serverIp)
                        
                        // 保存医院等信息
                        response.hostInfo?.let { hostInfo ->
                            preferenceManager.saveHostInfo(
                                hostInfo.hospital,
                                hostInfo.department,
                                hostInfo.location,
                                hostInfo.equipment
                            )
                        }
                        
                        // 显示提示对话框
                        showRegistrationSuccessDialog()
                    } else {
                        Toast.makeText(this@HostRegistrationActivity, response.message ?: "注册失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRegister.isEnabled = true
                    Toast.makeText(this@HostRegistrationActivity, "注册失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showRegistrationSuccessDialog() {
        AlertDialog.Builder(this)
            .setTitle("注册成功")
            .setMessage("请等待后台设备验证后，重新启动应用")
            .setPositiveButton("确认") { _, _ ->
                // 退出应用
                finishAffinity()
            }
            .setCancelable(false)
            .show()
    }
}

