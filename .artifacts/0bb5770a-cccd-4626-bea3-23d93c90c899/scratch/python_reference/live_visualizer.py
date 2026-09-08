# src/live_visualizer.py

from PyQt5.QtWidgets import QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QLabel, QApplication
from PyQt5.QtCore import Qt
import pyqtgraph as pg
import numpy as np
import config
from .console_logger import logger

class LiveVisualizer(QMainWindow):
    """
    This class creates the main window for the real-time visualization.
    It contains a pyqtgraph plot to display the radar's point cloud.
    """
    def __init__(self, worker, worker_thread):
        super().__init__()
        # --- Store references to the worker and its thread ---
        self.worker = worker
        self.worker_thread = worker_thread
        
        # --- NEW: Cache flags from worker to avoid RuntimeError during shutdown ---
        # The worker's underlying C++ object might be deleted before the final UI updates
        self.is_mrr = getattr(worker, 'is_mrr', False)
        self.is_bsd = getattr(worker, 'is_bsd', False)

        # --- Main Widget and Layout ---
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        main_layout = QHBoxLayout(central_widget)

        # --- Create the Plot Widget ---
        self.plot_widget = pg.PlotWidget()
        main_layout.addWidget(self.plot_widget, stretch=4)

        # --- Create Legend Panel ---
        legend_widget = QWidget()
        legend_widget.setStyleSheet("background-color: #1a1a1a; border-left: 1px solid #333;")
        legend_layout = QVBoxLayout(legend_widget)
        legend_layout.setAlignment(Qt.AlignTop)
        main_layout.addWidget(legend_widget, stretch=1)

        def add_legend_entry(text, color, symbol='o', size=15):
            row_widget = QWidget()
            row = QHBoxLayout(row_widget)
            row.setContentsMargins(5, 5, 5, 5)
            
            icon = QLabel()
            icon.setFixedSize(size, size)
            if symbol == 's': # Square
                icon.setStyleSheet(f"background-color: {color}; border: 1px solid white;")
            elif symbol == 't': # Triangle
                # Using border logic for triangle
                icon.setFixedSize(size, size)
                icon.setStyleSheet(f"width: 0; height: 0; border-left: {size//2}px solid transparent; "
                                   f"border-right: {size//2}px solid transparent; "
                                   f"border-bottom: {size}px solid {color}; background-color: transparent;")
            else: # Circle
                icon.setStyleSheet(f"background-color: {color}; border-radius: {size//2}px; border: 1px solid white;")
            
            label = QLabel(text)
            label.setStyleSheet("color: white; font-size: 13px; font-weight: normal;")
            
            row.addWidget(icon)
            row.addWidget(label)
            row.addStretch()
            legend_layout.addWidget(row_widget)

        legend_title = QLabel("LEGEND")
        legend_title.setStyleSheet("color: #00ff00; font-size: 18px; font-weight: bold; margin-bottom: 15px; padding: 5px;")
        legend_layout.addWidget(legend_title)

        is_mrr = self.is_mrr
        is_bsd = self.is_bsd

        # Adapt labels based on project
        pc_label = "MRR Points (SF0)" if is_mrr else ("BSD Points" if is_bsd else "Radar Points")
        add_legend_entry(pc_label, "white", 'o')
        
        if is_mrr:
            add_legend_entry("USRR Points (SF1)", "yellow", 's')
        
        if config.SHOW_SOFTWARE_TRACKS:
            add_legend_entry("Software Tracks", "rgba(0, 255, 0, 100)", 'o')
        
        if is_mrr and config.SHOW_HARDWARE_CLUSTERS:
            add_legend_entry("Hardware Clusters", "cyan", 's')
            
        if config.SHOW_HARDWARE_TRACKS:
            hw_track_label = "Hardware Tracks" if not is_mrr else "MRR Tracks"
            add_legend_entry(hw_track_label, "magenta", 't')

        # --- Configure Plot Aesthetics ---
        self.plot_widget.setBackground('k')
        self.plot_widget.setTitle("Live Radar Point Cloud", color="w", size="20pt")
        styles = {'color':'w', 'font-size':'15px'}
        self.plot_widget.setLabel('left', 'Y Position (m)', **styles)
        self.plot_widget.setLabel('bottom', 'X Position (m)', **styles)
        self.plot_widget.showGrid(x=True, y=True)
        self.plot_widget.setAspectLocked(True)

        # --- Set Initial Axis Ranges ---
        self.plot_widget.setXRange(-40, 40)
        self.plot_widget.setYRange(0, 80)

        # --- Create a Scatter Plot Item for Raw Point Cloud (MRR - SF0) ---
        self.plot_data_item = pg.ScatterPlotItem(
            size=5, pen=pg.mkPen(None), brush=pg.mkBrush(255, 255, 255, 150), symbol='o'
        )
        self.plot_widget.addItem(self.plot_data_item)

        # --- NEW: USRR Point Cloud (SF1 - Yellow Squares) ---
        self.usrr_plot_item = pg.ScatterPlotItem(
            size=4, pen=pg.mkPen(None), brush=pg.mkBrush(255, 255, 0, 150), symbol='s'
        )
        self.plot_widget.addItem(self.usrr_plot_item)
        
        # --- NEW: Software Tracks (Green Circles) ---
        self.sw_track_item = pg.ScatterPlotItem(
            size=12, pen=pg.mkPen('g', width=2), brush=pg.mkBrush(None), symbol='o'
        )
        self.plot_widget.addItem(self.sw_track_item)

        # --- NEW: Hardware Clusters (Cyan Squares) ---
        self.hw_cluster_item = pg.ScatterPlotItem(
            size=10, pen=pg.mkPen(None), brush=pg.mkBrush(0, 255, 255, 200), symbol='s'
        )
        self.plot_widget.addItem(self.hw_cluster_item)

        # --- NEW: Hardware Tracks (Magenta Triangles) ---
        self.hw_track_item = pg.ScatterPlotItem(
            size=15, pen=pg.mkPen(None), brush=pg.mkBrush(255, 0, 255, 200), symbol='t'
        )
        self.plot_widget.addItem(self.hw_track_item)

    def update_plot(self, frame_data):
        """
        Updates the plot with new point cloud data.
        """
        # Always clear software tracks as they move rapidly
        self.sw_track_item.clear()
        
        # --- Handle Subframe Logic (MRR only) ---
        # Use cached flag to avoid RuntimeError during shutdown
        is_mrr = self.is_mrr
        
        # Safely get header field (it's a dictionary in real-time)
        subframe_num = frame_data.header.get('subFrameNumber', 0) if isinstance(frame_data.header, dict) else getattr(frame_data.header, 'subFrameNumber', 0)
        is_usrr = is_mrr and (subframe_num == 1)

        # Update the title with the current frame and subframe
        frame_idx = 0
        tracks = []
        try:
            if self.worker and self.worker.tracker:
                frame_idx = self.worker.tracker.frame_idx
                # Get active software tracks
                tracks = [t for t in self.worker.tracker.all_tracks if t.get('isConfirmed') and not t.get('isLost')]
        except RuntimeError:
            # Worker or Tracker already deleted during shutdown
            pass
        
        # Get sensor stats
        stats = getattr(frame_data, 'sensor_stats', {})
        cpu_load = stats.get('activeFrameCPULoad', 0)
        proc_time = stats.get('interFrameProcessingTime', 0)
        
        self.plot_widget.setTitle(
            f"Live Radar View - Frame: {frame_idx} (SF{subframe_num}) | CPU: {cpu_load}% | Proc: {proc_time}us", 
            color="w", size="20pt"
        )

        # --- Update Raw Point Cloud based on Subframe ---
        if frame_data and frame_data.point_cloud is not None and frame_data.point_cloud.size > 0:
            x_coords = frame_data.point_cloud[0, :]
            y_coords = frame_data.point_cloud[1, :]
            
            finite_mask = np.isfinite(x_coords) & np.isfinite(y_coords)
            x_coords = x_coords[finite_mask]
            y_coords = y_coords[finite_mask]
            
            if is_usrr:
                # Update USRR (Yellow Squares)
                self.usrr_plot_item.setData(x=x_coords, y=y_coords)
            else:
                # Update MRR (White Circles)
                self.plot_data_item.setData(x=x_coords, y=y_coords)

        # --- Update Software Tracks (Always Cleared first, then conditionally updated) ---
        num_sw = 0
        if config.SHOW_SOFTWARE_TRACKS and tracks:
            x_sw = []
            y_sw = []
            for t in tracks:
                if t.get('historyLog'):
                    last_log = t['historyLog'][-1]
                    pos = last_log.get('correctedPosition')
                    if pos is not None and len(pos) >= 2:
                        x_sw.append(pos[0])
                        y_sw.append(pos[1])
            num_sw = len(x_sw)
            if num_sw > 0:
                self.sw_track_item.setData(x=x_sw, y=y_sw)
        
        # --- Update Hardware Clusters ---
        num_hw_c = 0
        if config.SHOW_HARDWARE_CLUSTERS and frame_data and hasattr(frame_data, 'mrr_clusters') and frame_data.mrr_clusters:
            x_clus = [c['x'] for c in frame_data.mrr_clusters]
            y_clus = [c['y'] for c in frame_data.mrr_clusters]
            num_hw_c = len(x_clus)
            self.hw_cluster_item.setData(x=x_clus, y=y_clus)
        else:
            self.hw_cluster_item.clear()

        # --- Update Hardware Tracks (Unified: MRR or BSD) ---
        num_hw_t = 0
        if config.SHOW_HARDWARE_TRACKS:
            x_track = []
            y_track = []
            
            # 1. Try MRR hardware tracks
            if frame_data and hasattr(frame_data, 'mrr_tracks') and frame_data.mrr_tracks:
                x_track.extend([t['x'] for t in frame_data.mrr_tracks])
                y_track.extend([t['y'] for t in frame_data.mrr_tracks])
                
            # 2. Try Standard BSD target list tracks (Structure-of-Arrays format)
            if frame_data and hasattr(frame_data, 'target_list') and frame_data.target_list:
                targets_soa = frame_data.target_list
                if 'tPos' in targets_soa:
                    x_track.extend(targets_soa['tPos'][0, :].tolist())
                    y_track.extend(targets_soa['tPos'][1, :].tolist())

            num_hw_t = len(x_track)
            if num_hw_t > 0:
                self.hw_track_item.setData(x=x_track, y=y_track)
            else:
                self.hw_track_item.clear()
        else:
            self.hw_track_item.clear()

        # logger.debug(f"[VISUALIZER] Plot updated: PC={num_pc}, SW_T={num_sw}, HW_C={num_hw_c}, HW_T={num_hw_t}")

    def closeEvent(self, event):
        """
        This method is called when the user closes the window.
        It handles the graceful shutdown of the worker thread.
        """
        logger.info("--- Window closed. Initiating shutdown... ---")
        
        # 1. Signal the worker to stop its loop (Ungraceful for GUI close)
        # We pass graceful=False because if the user closes the window, 
        # they usually want to exit immediately without waiting for post-processing.
        try:
            self.worker.stop(graceful=False)
        except TypeError:
            # Fallback for workers that don't support the graceful flag yet
            self.worker.stop()
        
        # 2. Tell the QThread to quit its event loop if it's still running
        try:
            if self.worker_thread and self.worker_thread.isRunning():
                self.worker_thread.quit()
                self.worker_thread.wait()
        except RuntimeError:
            logger.debug("Worker thread already deleted, skipping shutdown wait.")
        
        logger.info("--- Shutdown complete. ---")
        event.accept() # Allow the window to close