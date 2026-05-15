#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use tauri::{Manager, State};
use std::sync::Mutex;
use std::process::{Command, Stdio};

struct AppState {
    ffmpeg_process: Mutex<Option<std::process::Child>>,
}

#[tauri::command]
fn get_local_ip() -> Result<String, String> {
    local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .map_err(|e| e.to_string())
}

#[tauri::command]
fn start_stream(state: State<AppState>, url: String) -> Result<String, String> {
    let mut guard = state.ffmpeg_process.lock().unwrap();

    if let Some(mut child) = guard.take() {
        let _ = child.kill();
    }

    let child = Command::new("ffplay")
        .args(&[
            "-fflags", "nobuffer",
            "-flags", "low_delay",
            "-rtsp_transport", "tcp",
            "-i", &url,
            "-vf", "scale=1280:720",
            "-f", "rawvideo",
            "-pix_fmt", "rgb24",
            "-s", "1280x720",
            "-"
        ])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| e.to_string())?;

    *guard = Some(child);
    Ok("Stream started".to_string())
}

#[tauri::command]
fn stop_stream(state: State<AppState>) -> Result<(), String> {
    let mut guard = state.ffmpeg_process.lock().unwrap();
    if let Some(mut child) = guard.take() {
        let _ = child.kill();
    }
    Ok(())
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .manage(AppState { ffmpeg_process: Mutex::new(None) })
        .invoke_handler(tauri::generate_handler![get_local_ip, start_stream, stop_stream])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
