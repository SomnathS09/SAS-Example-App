package com.example.sasexample

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.example.sasexample.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {
    private var _binding  : FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentHomeBinding.inflate(inflater, container, false)

        binding.createRecInstanceBtn.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_recordingFragment)
        }

        return binding.root
    }

}