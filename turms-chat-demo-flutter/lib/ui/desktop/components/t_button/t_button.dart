import 'dart:math';

import 'package:flutter/material.dart';

import '../../../themes/index.dart';
import '../t_tooltip/t_tooltip.dart';

class TButton extends StatefulWidget {
  const TButton(
      {super.key,
      required this.addContainer,
      this.containerWidth,
      this.containerHeight,
      this.containerBlendMode,
      this.containerColor,
      this.containerColorHovered,
      this.containerColorPressed,
      this.containerPadding,
      this.containerBorder,
      this.containerBorderHovered,
      this.containerBorderRadius = Sizes.borderRadiusCircular4,
      this.duration = Durations.short4,
      this.isLoading = false,
      this.disabled = false,
      this.tooltip,
      this.onTap,
      this.onPanDown,
      this.prefix,
      this.childHovered,
      this.childPressed,
      required this.child});

  final bool addContainer;
  final double? containerWidth;
  final double? containerHeight;
  final BlendMode? containerBlendMode;
  final Color? containerColor;
  final Color? containerColorHovered;
  final Color? containerColorPressed;
  final EdgeInsets? containerPadding;
  final BoxBorder? containerBorder;
  final BoxBorder? containerBorderHovered;
  final BorderRadiusGeometry? containerBorderRadius;
  final Duration duration;

  final bool isLoading;
  final bool disabled;
  final String? tooltip;
  final VoidCallback? onTap;
  final GestureDragDownCallback? onPanDown;

  final Widget? prefix;
  final Widget? childHovered;
  final Widget? childPressed;
  final Widget child;

  @override
  State<TButton> createState() => _TButtonState();
}

class _TButtonState extends State<TButton> {
  bool _isHovered = false;
  bool _isPressed = false;

  @override
  Widget build(BuildContext context) {
    final theme = context.theme;
    var child = _isPressed
        ? (widget.childPressed ?? widget.childHovered ?? widget.child)
        : _isHovered
            ? widget.childHovered ?? widget.child
            : widget.child;
    if (widget.prefix case final prefix?) {
      child = Row(
          mainAxisSize: MainAxisSize.min,
          spacing: 8,
          children: [prefix, child]);
    }
    if (widget.isLoading) {
      child = Stack(
        children: [
          Visibility.maintain(
            child: child,
            visible: false,
          ),
          Positioned.fill(child: Center(
            child: LayoutBuilder(
              builder: (BuildContext context, BoxConstraints constraints) {
                final size =
                    min(constraints.maxWidth, constraints.maxHeight) * 0.8;
                return SizedBox(
                  width: size,
                  height: size,
                  child: const CircularProgressIndicator(
                    color: Colors.white,
                  ),
                );
              },
            ),
          ))
        ],
      );
    }
    if (widget.addContainer) {
      child = AnimatedContainer(
          duration: widget.duration,
          decoration: BoxDecoration(
            backgroundBlendMode: widget.containerBlendMode,
            color: widget.disabled
                ? theme.disabledColor
                : widget.isLoading
                    ? widget.containerColor?.withValues(alpha: 0.5)
                    : _isPressed && widget.containerColorPressed != null
                        ? widget.containerColorPressed!
                        : _isHovered
                            ? (widget.containerColorHovered ??
                                widget.containerColor?.withValues(alpha: 0.8))
                            : widget.containerColor,
            borderRadius: widget.containerBorderRadius,
            border: _isHovered
                ? (widget.containerBorderHovered ?? widget.containerBorder)
                : widget.containerBorder,
          ),
          padding: widget.containerPadding,
          width: widget.containerWidth,
          height: widget.containerHeight,
          child: child);
    }
    if (widget.tooltip case final tooltip?) {
      child = TTooltip(
        message: tooltip,
        preferBelow: true,
        waitDuration: Durations.extralong4,
        child: child,
      );
    }
    return RepaintBoundary(
      child: MouseRegion(
          cursor: widget.disabled
              ? SystemMouseCursors.forbidden
              : SystemMouseCursors.click,
          onEnter: (_) {
            _isHovered = true;
            setState(() {});
          },
          onExit: (_) {
            _isHovered = false;
            _isPressed = false;
            setState(() {});
          },
          child: GestureDetector(
            onTap: !widget.disabled && !widget.isLoading && widget.onTap != null
                ? widget.onTap
                : null,
            onPanDown: (details) {
              _isPressed = true;
              widget.onPanDown?.call(details);
              setState(() {});
            },
            child: child,
          )),
    );
  }
}